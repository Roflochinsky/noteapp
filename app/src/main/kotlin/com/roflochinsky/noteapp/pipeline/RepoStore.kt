package com.roflochinsky.noteapp.pipeline

import java.io.File
import java.io.IOException
import java.time.LocalDate

/** Что показать под шапкой одной строкой (без диалогов и тостов). */
enum class SyncStatus {
    OK,
    NO_TOKEN,
    OFFLINE,
    NO_ACCESS,
    RATE_LIMIT,
}

/**
 * Единственная точка входа UI к репо заметок: экран просит задачи, правки и обновление, про кэш,
 * очередь и HTTP не знает ничего (решение LLD-24).
 *
 * Правка ложится в журнал очереди и сразу видна поверх кэша (pending-overlay, решение LLD-5):
 * галочка не отскакивает и офлайн работает сам собой. Отправку ведёт [push] — по одной операции за
 * вызов, паузу между мутациями держит воркер (research §3.3).
 *
 * ponytail: чтение полное (ref + tree + изменившиеся блобы) — репо личное и маленькое; ETag,
 * `compare` и дельта живут в срезе Н7, раньше они экономят то, чего никто не тратит.
 */
@Suppress("TooManyFunctions") // разделение фасада и отправки — bd nikitatrubaev-0rk.14
class RepoStore(
    private val cache: RepoCache,
    private val api: GithubApi?,
    private val queue: WriteQueue = WriteQueue(File(cache.dir, QUEUE)),
    private val clock: () -> LocalDate = LocalDate::now,
) {

    /**
     * Сегодняшняя дата берётся в момент использования, а не в конструктор: фасад один на процесс
     * (`shared()`) и живёт дольше суток. Замороженная дата дала бы задаче, заведённой после
     * полуночи, вчерашнее имя файла и вчерашний `created:`, а экран рисовал бы `LocalDate.now()`.
     */
    private val today: LocalDate
        get() = clock()

    /** Итог одной отправки: очередь пуста, есть ещё, ждём сети/лимита, наш баг. */
    enum class Push {
        EMPTY,
        MORE,
        RETRY,
        FAILED,
    }

    /**
     * Снимок читается через кэш, а не в конструктор: экранный экземпляр и экземпляр воркера записи
     * держат один и тот же кэш, и экран обязан увидеть коммит воркера — иначе галочка отскакивает в
     * «Открыта» (решение LLD-5).
     */
    private val snapshot: RepoCache.Snapshot
        get() = cache.snapshot()

    /** Замок файла сообщений: сетью не занят, поэтому главный поток на нём не залипает. */
    private val notices = Any()

    /**
     * Фасад заменён: владелец сменил в настройках токен или репо, и [shared] отдал экрану новый
     * экземпляр. Воркер записи при этом до конца `doWork()` держит СВОЙ, локальный — и это ровно
     * два фасада над одним кэшем, гонка Б1 узким окном. Ждать здесь нечего: замок [push] держится
     * через сеть, а зовут [shared] с экрана. Поэтому отправку обрываем на входе — [Push.RETRY]
     * возвращает воркера в цепочку, а следующий заход возьмёт из [shared] свежий экземпляр.
     *
     * `@Volatile`: пишет экран под замком [shared] (монитор компаньона), читает воркер под замком
     * [push] (монитор экземпляра) — разные мониторы, связи между записью и чтением иначе нет.
     */
    @Volatile private var retired = false

    /**
     * id операции, которую [push] прямо сейчас отправляет. Пока PUT в полёте, «ОТМЕНИТЬ» её снять
     * не может: коммит доедет, а запись журнала исчезла бы — интерфейс соврал бы владельцу.
     *
     * `@Volatile`: пишет поток воркера в [push], читает поток экрана в [cancel].
     *
     * ponytail: остаточное окно — два соседних оператора в [push], между чтением головы журнала и
     * пометкой. Отмена, попавшая ровно туда, снова соврёт. Закрыть его до конца можно только
     * пометкой ПОД замком журнала, то есть ещё одной парой методов в [WriteQueue] (порог detekt на
     * число функций она перебирает). Меняем секунды сетевого запроса на наносекунды между двумя
     * присваиваниями — владельцу этой разницы не видно.
     */
    @Volatile private var flying: String? = null

    /**
     * Всё, что экран рисует прямо сейчас: задачи из кэша поверх ожидающих правок (решение LLD-5),
     * пути в янтарном «в очереди» и разовое сообщение о расхождении. Один вызов, а не четыре: иначе
     * половина снимка успевает устареть, пока читается вторая.
     */
    data class View(
        val revision: String,
        val tasks: List<TaskFile.Task>,
        val pending: Set<String>,
        val notice: String?,
        /** Значения чипа «Проект» — из реестра `projects.md`, а не из проставленного в задачах. */
        val projects: List<String> = emptyList(),
    )

    fun view(): View {
        val ops = queue.pending()
        val snapshot = snapshot
        return View(
            revision = revision(ops),
            tasks = overlay(ops, snapshot).map { (path, text) -> TaskFile.parse(path, text) },
            pending = ops.map { it.path }.toSet(),
            notice = takeDivergence(),
            projects = projects(ops, snapshot),
        )
    }

    /**
     * Дешёвая метка того, что видно на экране: кэш плюс журнал. Не изменилась — пересобирать список
     * не из чего, и репо-кэш остаётся вне секундного поллинга (вердикт UX).
     */
    fun revision(): String = revision(queue.pending())

    /** Пути, ждущие отправки: по ним экран решает, заводить ли воркер после перезапуска. */
    fun pendingPaths(): Set<String> = queue.pending().map { it.path }.toSet()

    fun edit(path: String, edit: Edit): String = queue.enqueue(path, edit).id

    /** Дата закрытия ставится вместе со статусом — считает её store, а не экран. */
    fun setStatus(path: String, status: String): String =
        edit(path, Edit.SetStatus(status, today.takeIf { status == TaskFile.STATUS_DONE }))

    fun create(
        title: String,
        project: String? = null,
        priority: String = TaskFile.PRIORITY_DEFAULT,
        due: LocalDate? = null,
        tags: List<String> = emptyList(),
    ): String {
        val taken = snapshot.files.keys + queue.pending().map { it.path }
        val path = TaskFile.DIR + TaskFile.fileName(today, title, taken)
        val text =
            TaskFile.build(
                TaskFile.Task(
                    path = path,
                    title = title.trim(),
                    priority = priority,
                    project = project,
                    created = today,
                    due = due,
                    tags = tags,
                )
            )
        queue.enqueue(path, Edit.CreateTask(text))
        return path
    }

    /**
     * Новый проект из шторки — строкой в `projects.md` (комп v2, борд 2, `bd
     * nikitatrubaev-0rk.23`). Реестр едет теми же рельсами, что и задачи: операция в журнале,
     * коммит воркером, кэш из ответа записи. Дубликат отсекает [Registry.add] в момент отправки —
     * на тексте из git, а не на нашем кэше: пока правка ждала сети, тот же проект мог завести
     * Action или второе устройство.
     */
    fun addProject(name: String): String = edit(Registry.PROJECTS, Edit.AddToRegistry(name))

    fun delete(path: String): String = edit(path, Edit.DeleteFile)

    /**
     * Отмена снекбаром до отправки: операция снимается, второго коммита не будет.
     *
     * А если [push] уже взял её в отправку — снять нечего: PUT в полёте, коммит доедет. Тогда
     * говорим об этом плашкой, тем же каналом [say], которым воркер сообщает о снятых правках:
     * экран после отмены всё равно перечитывает `view()`, так что сообщение видно сразу. Молчать
     * нельзя — владелец решил бы, что отменил.
     */
    fun cancel(id: String) {
        if (id == flying || !queue.cancel(id)) {
            say("Правка уже ушла в GitHub — отменить не успели")
        }
    }

    /**
     * Расхождение по 409 показывается один раз и плашкой, не модалкой (вердикт UX).
     *
     * Чтение и стирание — под [notices], иначе [say] воркера, попавший между `readText()` и
     * `delete()`, стирается непрочитанным (ровно то свойство, ради которого там появился
     * `appendText`). Монитор нужен СВОЙ, а не общий с [push]: главного потока здесь нет — [view]
     * зовётся из `withContext(Dispatchers.IO)` (`MainActivity.reload`), — но замок [push] держится
     * через сеть, до минут, и пересбор списка на экране ждал бы отправки. Под [notices] сети нет —
     * только два файловых вызова.
     */
    private fun takeDivergence(): String? =
        synchronized(notices) {
            val file = File(cache.dir, DIVERGENCE)
            val text = file.takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }
            file.delete()
            text
        }

    /**
     * Обновление и отправка — единственные два места, которые кэш переписывают, и в одном процессе
     * они идут параллельно: экран зовёт [refresh] из pull-to-refresh, воркер записи — [push]. Оба
     * сетевые, оба «прочитать снимок → записать снимок», и без замка выигрывает тот, чей `save()`
     * лёг вторым: обновление кладёт дерево, прочитанное ДО коммита воркера, галочка отскакивает в
     * «Открыта» (блокер Б1).
     *
     * ponytail: обычный монитор, а не файловая блокировка и не `Mutex`. Писатель один — воркер
     * живёт в том же процессе, что и экран (`android:process` в манифесте нет), а сами методы
     * блокирующие.
     *
     * **Честный потолок ожидания — минуты, а не «один запрос».** [GithubClient.TIMEOUT_MS] — 60
     * секунд и на соединение, и на чтение, а под замком висит не один запрос:
     * - pull-to-refresh ждёт [push]: PUT, а на 409 ещё чтение чужого файла и повтор — до трёх
     *   запросов подряд;
     * - воркер записи ждёт [refresh]: чтение ref, чтение дерева и по блобу на КАЖДЫЙ изменившийся
     *   файл — на холодном старте это всё дерево задач.
     *
     * Терпимо ровно потому, что монитор берут только эти двое и оба — фоновые: главный поток правит
     * через [edit]/[create]/[cancel], а они замка не берут вовсе (журнал очереди сторожит свой
     * собственный монитор, сетью не занятый). Пауза в секунду между мутациями держится между
     * вызовами [push], то есть вне замка.
     */
    @Synchronized
    fun refresh(): SyncStatus {
        val api = api ?: return SyncStatus.NO_TOKEN
        return try {
            val commit = api.readRef()
            val tree = api.readTree(commit)
            val waiting = queue.pending().map { it.path }.toSet()
            val known = snapshot.files
            val files =
                tree
                    .filterKeys { isTask(it) || it in REGISTRIES }
                    .mapValues { (path, sha) ->
                        // Путь с ожидающей правкой не перечитываем: кэш держит базу слияния.
                        known[path]?.takeIf { it.sha == sha || path in waiting }
                            ?: RepoCache.Entry(sha, api.readBlob(sha))
                    }
            cache.save(RepoCache.Snapshot(commit, files))
            SyncStatus.OK
        } catch (e: GithubHttpException) {
            status(e)
        } catch (@Suppress("SwallowedException", "TooGenericExceptionCaught") e: Exception) {
            // Ловим шире IOException намеренно: ответ разбирается через org.json, а JSONException
            // ему не родня. Ответ 200 с не-JSON телом (кэптив-портал, HTML от CDN) ронял корутину
            // экрана целиком — для владельца это неотличимо от «нет сети», ей и показываем.
            SyncStatus.OFFLINE
        }
    }

    /**
     * Одна операция за вызов: паузу в секунду между мутациями держит воркер (research §4).
     *
     * Взятая операция помечается «в полёте» ([flying]): пока PUT идёт по сети, [cancel] снять её
     * уже не может — коммит доедет. Пометка снимается в `finally`: отправка кончилась хоть успехом,
     * хоть отказом, хоть исключением.
     */
    @Synchronized
    fun push(): Push {
        if (retired) return Push.RETRY
        val op = queue.pending().firstOrNull() ?: return Push.EMPTY
        flying = op.id
        return try {
            deliver(op)
        } finally {
            flying = null
        }
    }

    private fun deliver(op: WriteQueue.Op): Push {
        val api = api ?: return Push.RETRY
        return try {
            when (val edit = op.edit) {
                is Edit.CreateTask -> born(api, op, edit)
                Edit.DeleteFile -> gone(api, op)
                is Edit.AddToRegistry -> listed(api, op, edit)
                else -> sent(api, op)
            }
        } catch (e: GithubHttpException) {
            http(api, op, e)
        } catch (@Suppress("SwallowedException") e: IOException) {
            Push.RETRY
        }
    }

    /**
     * Базу правки берём из кэша, а если он путь потерял (холодный старт, чужой коммит унёс его из
     * дерева) — читаем из git. Молча выбрасывать правку нельзя: янтарное «в очереди» погасло бы,
     * коммита нет, и владелец об этом не узнал бы. Файла нет вовсе — [readFile] отдаст 404, и это
     * уже [vanished] с сообщением.
     */
    private fun sent(api: GithubApi, op: WriteQueue.Op): Push {
        val entry = snapshot.files[op.path] ?: api.readFile(op.path)
        val content = Edit.apply(entry.text, op.edit)
        val written = api.putFile(op.path, content, message(op), entry.sha)
        accept(op.path, RepoCache.Entry(written.sha ?: entry.sha, content), written.commitSha)
        return drop(op)
    }

    private fun born(api: GithubApi, op: WriteQueue.Op, edit: Edit.CreateTask): Push {
        val written = api.putFile(op.path, edit.content, message(op), null)
        accept(op.path, RepoCache.Entry(written.sha.orEmpty(), edit.content), written.commitSha)
        return drop(op)
    }

    /**
     * Реестр — не задача: строки в нём независимы, сливать трёхсторонне нечего, а [Registry.add]
     * идемпотентен. Поэтому у него свой путь отправки рядом с [born] и [gone]: дописать строку в
     * тот текст, что лежит в git сейчас, — и переиграть на 409, а не показывать расхождение.
     *
     * ponytail: реестра в репо нет вовсе — 404 уйдёт общей веткой [vanished]; сообщение назовёт его
     * задачей, зато владелец узнает. Заводить `projects.md` за владельца приложение не берётся:
     * реестр — часть раскладки репо заметок (ADR `2026-08-26-tasks-as-files.md`).
     */
    private fun listed(api: GithubApi, op: WriteQueue.Op, edit: Edit.AddToRegistry): Push {
        val entry = snapshot.files[op.path] ?: api.readFile(op.path)
        // Имя уже в реестре — цель владельца достигнута чужим коммитом, пустой коммит не нужен.
        val text = Registry.add(entry.text, edit.name) ?: return drop(op)
        return try {
            val written = api.putFile(op.path, text, message(op), entry.sha)
            accept(op.path, RepoCache.Entry(written.sha ?: entry.sha, text), written.commitSha)
            drop(op)
        } catch (e: GithubHttpException) {
            if (e.code != HTTP_CONFLICT) throw e
            replay(api, op, edit)
        }
    }

    /**
     * Реестр изменили под нами. Чужая строка нашей не мешает — перечитываем и дописываем заново.
     * Кончились попытки — говорим владельцу про реестр и про его проект: молчать нельзя, он видел
     * имя в чипе и считает, что оно записано.
     */
    private fun replay(api: GithubApi, op: WriteQueue.Op, edit: Edit.AddToRegistry): Push {
        accept(op.path, api.readFile(op.path), commitSha = "")
        if (op.attempt < ConflictRule.MAX_REPLAYS) {
            queue.retry(op)
            return Push.MORE
        }
        say("Проект «${edit.name}» не записан в ${op.path} — реестр меняли одновременно, повторите")
        return drop(op)
    }

    private fun gone(api: GithubApi, op: WriteQueue.Op): Push {
        val sha = snapshot.files[op.path]?.sha ?: api.readFile(op.path).sha
        val written = api.deleteFile(op.path, message(op), sha)
        forget(op.path, written.commitSha)
        return drop(op)
    }

    /** 409 — штатная ветка (research §7): перечитать, слить трёхсторонне, переиграть. */
    private fun conflict(api: GithubApi, op: WriteQueue.Op): Push {
        if (op.attempt >= ConflictRule.MAX_REPLAYS) return diverged(op, op.edit.fields)
        val theirs = api.readFile(op.path)
        // Кэш базу потерял — считаем базой то, что сейчас в git: правка ляжет поверх, а не
        // пропадёт.
        val base = snapshot.files[op.path] ?: theirs
        val outcome =
            ConflictRule.resolve(
                base = TaskFile.parse(op.path, base.text),
                mine = TaskFile.parse(op.path, Edit.apply(base.text, op.edit)),
                theirs = TaskFile.parse(op.path, theirs.text),
            )
        // Чужой текст приехал из коммита, которого мы не знаем: `readFile` его не называет.
        // Оставить прежний sha — соврать («коммит, на котором мы синхронизированы», KDoc
        // RepoCache): этого текста тот коммит не содержит. Пустой sha и значит «не знаем».
        accept(op.path, theirs, commitSha = "")
        return when (outcome) {
            is ConflictRule.Divergence -> diverged(op, outcome.fields)
            // Правки не пересеклись: переигрываем ту же Edit поверх свежего текста.
            ConflictRule.Merged -> {
                queue.retry(op)
                Push.MORE
            }
        }
    }

    private fun http(api: GithubApi, op: WriteQueue.Op, e: GithubHttpException): Push =
        when (e.code) {
            HTTP_CONFLICT -> conflict(api, op)
            HTTP_NOT_FOUND -> vanished(op)
            HTTP_UNPROCESSABLE ->
                if (op.edit is Edit.CreateTask) {
                    say("Файл ${op.path} уже есть в GitHub — задача не создана")
                    drop(op)
                } else {
                    // Наш баг (забыли sha, кривой автор): ретрай не поможет. Операцию снимаем —
                    // иначе она встаёт в голову журнала навсегда и запирает всё, что за ней, а
                    // снять её владельцу нечем: `cancel` живёт пять секунд в снекбаре. Цепочку
                    // при этом гасим (`FAILED` → `Result.failure()`), чтобы баг был виден.
                    say("Правку ${op.path} GitHub не принял — она снята, повторите")
                    drop(op)
                    Push.FAILED
                }
            else -> Push.RETRY
        }

    /**
     * Путь уехал (Action перенёс или файла уже нет): правку выбрасываем, файл не воскрешаем, а
     * карту приводим в чувство — иначе владелец продолжает видеть задачу, которой в репо нет
     * (решение LLD-8).
     */
    private fun vanished(op: WriteQueue.Op): Push {
        forget(op.path, snapshot.commitSha)
        say(
            if (op.edit is Edit.DeleteFile) {
                // Удаление 404 — это не потеря: цель владельца достигнута. Сюда же приходит
                // удаление задачи, которая до GitHub не доехала (создание вытеснено из журнала
                // тем же удалением), — говорить про неё «больше нет» значит врать: её там и не
                // было (находка Д13).
                "Задачи ${op.path} в GitHub нет — удалять нечего"
            } else {
                "Задачи ${op.path} в GitHub больше нет — правка не применена"
            }
        )
        return drop(op)
    }

    private fun diverged(op: WriteQueue.Op, fields: List<String>): Push {
        val named = fields.filter { it != Edit.DONE || fields.size == 1 }
        say(
            if (named.isEmpty()) {
                "Задача изменилась в GitHub — правка не применена"
            } else {
                val labels = named.joinToString(", ") { "«${ConflictRule.label(it)}»" }
                "Поле $labels изменилось в GitHub — оставлено значение из репо"
            }
        )
        return drop(op)
    }

    /**
     * Сообщение владельцу дописывается, а не перезаписывается: за один прогон воркер снимает
     * несколько операций (чужое удаление, наш баг), и владелец должен увидеть все, а не только
     * последнюю. Плашка читает файл целиком и стирает его (см. [takeDivergence]).
     */
    private fun say(text: String) =
        synchronized(notices) {
            cache.dir.mkdirs()
            val file = File(cache.dir, DIVERGENCE)
            file.appendText(if (file.length() == 0L) text else "\n$text")
        }

    private fun drop(op: WriteQueue.Op): Push {
        queue.done(op)
        return Push.MORE
    }

    private fun accept(path: String, entry: RepoCache.Entry, commitSha: String) {
        // Кэш обновляется из ответа записи: отдельный опрос ref не нужен (решение LLD-4).
        val was = snapshot
        cache.save(was.copy(commitSha = commitSha, files = was.files + (path to entry)))
    }

    /** Пути в репо больше нет: убираем его из карты, чтобы владелец не видел призрак. */
    private fun forget(path: String, commitSha: String) {
        val was = snapshot
        cache.save(was.copy(commitSha = commitSha, files = was.files - path))
    }

    private fun revision(ops: List<WriteQueue.Op>): String =
        cache.stamp() + ops.joinToString(",") { "${it.id}#${it.attempt}" }

    /** Кэш + журнал: то, что владелец видит на экране прямо сейчас. */
    private fun overlay(
        ops: List<WriteQueue.Op>,
        snapshot: RepoCache.Snapshot,
    ): Map<String, String> {
        val texts = LinkedHashMap<String, String>()
        snapshot.files.filterKeys { isTask(it) }.forEach { (path, e) -> texts[path] = e.text }
        ops.filter { isTask(it.path) }
            .forEach { op ->
                when (val edit = op.edit) {
                    Edit.DeleteFile -> texts.remove(op.path)
                    is Edit.CreateTask -> texts[op.path] = edit.content
                    else -> texts[op.path]?.let { texts[op.path] = Edit.apply(it, edit) }
                }
            }
        return texts
    }

    /**
     * Значения чипа «Проект»: реестр из кэша плюс имена, которые ещё ждут отправки. Без второй
     * половины проект, заведённый из шторки, пропадал бы из выбора до первого коммита — а задача с
     * ним уже создана, и отфильтровать её было бы нечем (`bd nikitatrubaev-0rk.23`).
     */
    private fun projects(ops: List<WriteQueue.Op>, snapshot: RepoCache.Snapshot): List<String> =
        (Registry.names(snapshot.files[Registry.PROJECTS]?.text) +
                ops.filter { it.path == Registry.PROJECTS }
                    .mapNotNull { (it.edit as? Edit.AddToRegistry)?.name })
            .distinct()

    private fun isTask(path: String): Boolean =
        path.startsWith(TaskFile.DIR) && path.endsWith(".md")

    private fun message(op: WriteQueue.Op): String {
        val name = op.path.substringAfterLast('/')
        return when (val edit = op.edit) {
            is Edit.CreateTask -> "Новая задача $name"
            Edit.DeleteFile -> "Удалена задача $name"
            is Edit.AddToRegistry -> "Новый проект ${edit.name} в $name"
            else -> "Правка задачи $name"
        }
    }

    private fun status(e: GithubHttpException): SyncStatus =
        when (e.code) {
            HTTP_UNAUTHORIZED,
            HTTP_NOT_FOUND -> SyncStatus.NO_ACCESS
            HTTP_FORBIDDEN,
            HTTP_TOO_MANY -> SyncStatus.RATE_LIMIT
            else -> SyncStatus.OFFLINE
        }

    companion object {
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_CONFLICT = 409
        private const val HTTP_UNPROCESSABLE = 422
        private const val HTTP_TOO_MANY = 429
        private const val QUEUE = "queue"

        /** Не задачи, но приложению нужны: значения чипов приходят отсюда. */
        private val REGISTRIES = setOf(Registry.PROJECTS, Registry.PEOPLE)
        private const val DIVERGENCE = "divergence.txt"

        /** Кэш репо на телефоне — `files/repo/`. */
        fun cacheDir(filesDir: File): File = File(filesDir, "repo")

        private var shared: RepoStore? = null
        private var sharedKey = ""

        /**
         * Один фасад на процесс — и экрану, и воркеру записи. Воркер WorkManager живёт в том же
         * процессе, что и экран (`android:process` в манифесте нет), поэтому второй экземпляр над
         * тем же файлом кэша — не требование архитектуры, а гонка на ровном месте: два «прочитать
         * снимок → записать снимок» перетирали друг друга, и правка владельца исчезала (блокер Б1).
         *
         * Ключ — каталог кэша, репо и токен: сменились в настройках — фасад пересобирается, и
         * данные, прочитанные отозванным токеном, на экране не остаются (решение LLD-23).
         */
        @Synchronized
        fun shared(cacheDir: File, repo: String, token: String?, api: GithubApi?): RepoStore {
            val key = "${cacheDir.path}|$repo|${token.orEmpty().hashCode()}"
            val kept = shared
            if (kept != null && sharedKey == key) return kept
            // Прежний фасад с этой минуты не наш: воркер, который держит его локально, свою
            // отправку обрывает (см. [retired]), а не дописывает вторым писателем в тот же кэш.
            kept?.retired = true
            return RepoStore(RepoCache(cacheDir, repo, token), api).also {
                shared = it
                sharedKey = key
            }
        }
    }
}
