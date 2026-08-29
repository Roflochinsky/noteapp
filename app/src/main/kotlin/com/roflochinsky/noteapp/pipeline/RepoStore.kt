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
class RepoStore(
    private val repo: String,
    private val cache: RepoCache,
    private val api: GithubApi?,
    private val queue: WriteQueue = WriteQueue(File(cache.dir, QUEUE)),
    private val today: LocalDate = LocalDate.now(),
) {

    /** Итог одной отправки: очередь пуста, есть ещё, ждём сети/лимита, наш баг. */
    enum class Push {
        EMPTY,
        MORE,
        RETRY,
        FAILED,
    }

    private var snapshot = cache.load(repo)

    /** Задачи из кэша поверх ожидающих правок — рисуются мгновенно, без сети (решение LLD-12). */
    fun tasks(): List<TaskFile.Task> = overlay().map { (path, text) -> TaskFile.parse(path, text) }

    /** Путь ждёт отправки: в мета-строке и статус-строке это янтарное «в очереди». */
    fun isPending(path: String): Boolean = queue.pending(path).isNotEmpty()

    fun edit(path: String, edit: Edit): String = queue.enqueue(path, edit, sha(path)).id

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
        queue.enqueue(path, Edit.CreateTask(text), null)
        return path
    }

    fun delete(path: String): String = edit(path, Edit.DeleteFile)

    /** Отмена снекбаром до отправки: операция снимается, второго коммита не будет. */
    fun cancel(id: String) = queue.cancel(id)

    /** Расхождение по 409 показывается один раз и снекбаром, не модалкой (вердикт UX). */
    fun takeDivergence(): String? {
        val file = File(cache.dir, DIVERGENCE)
        val text = file.takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }
        file.delete()
        return text
    }

    fun refresh(): SyncStatus {
        val api = api ?: return SyncStatus.NO_TOKEN
        return try {
            val commit = api.readRef()
            val tree = api.readTree(commit)
            val waiting = queue.pending().map { it.path }.toSet()
            val files =
                tree
                    .filterKeys { isTask(it) }
                    .mapValues { (path, sha) ->
                        // Путь с ожидающей правкой не перечитываем: кэш держит базу слияния.
                        snapshot.files[path]?.takeIf { it.sha == sha || path in waiting }
                            ?: RepoCache.Entry(sha, api.readBlob(sha))
                    }
            snapshot = RepoCache.Snapshot(commit, files)
            cache.save(repo, snapshot)
            SyncStatus.OK
        } catch (e: GithubHttpException) {
            status(e)
        } catch (@Suppress("SwallowedException") e: IOException) {
            SyncStatus.OFFLINE
        }
    }

    /** Одна операция за вызов: паузу в секунду между мутациями держит воркер (research §4). */
    fun push(): Push {
        val op = queue.pending().firstOrNull() ?: return Push.EMPTY
        val api = api ?: return Push.RETRY
        return try {
            when (val edit = op.edit) {
                is Edit.CreateTask -> born(api, op, edit)
                Edit.DeleteFile -> gone(api, op)
                else -> sent(api, op)
            }
        } catch (e: GithubHttpException) {
            http(api, op, e)
        } catch (@Suppress("SwallowedException") e: IOException) {
            Push.RETRY
        }
    }

    private fun sent(api: GithubApi, op: WriteQueue.Op): Push {
        val entry = snapshot.files[op.path] ?: return drop(op)
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

    private fun gone(api: GithubApi, op: WriteQueue.Op): Push {
        val sha = snapshot.files[op.path]?.sha ?: api.readFile(op.path).sha
        val written = api.deleteFile(op.path, message(op), sha)
        snapshot =
            snapshot.copy(commitSha = written.commitSha, files = snapshot.files - op.path)
        cache.save(repo, snapshot)
        return drop(op)
    }

    /** 409 — штатная ветка (research §7): перечитать, слить трёхсторонне, переиграть. */
    private fun conflict(api: GithubApi, op: WriteQueue.Op): Push {
        val base = snapshot.files[op.path] ?: return drop(op)
        if (op.attempt >= ConflictRule.MAX_REPLAYS) return diverged(op, op.edit.fields)
        val theirs = api.readFile(op.path)
        val outcome =
            ConflictRule.resolve(
                base = TaskFile.parse(op.path, base.text),
                mine = TaskFile.parse(op.path, Edit.apply(base.text, op.edit)),
                theirs = TaskFile.parse(op.path, theirs.text),
            )
        accept(op.path, theirs, snapshot.commitSha)
        return when (outcome) {
            is ConflictRule.Divergence -> diverged(op, outcome.fields)
            is ConflictRule.Merged -> {
                queue.retry(op)
                Push.MORE
            }
        }
    }

    private fun http(api: GithubApi, op: WriteQueue.Op, e: GithubHttpException): Push =
        when (e.code) {
            HTTP_CONFLICT -> conflict(api, op)
            // Путь уехал (Action перенёс или файла уже нет) — правку выбрасываем, не воскрешаем.
            HTTP_NOT_FOUND -> drop(op)
            HTTP_UNPROCESSABLE ->
                if (op.edit is Edit.CreateTask) {
                    say("Файл ${op.path} уже есть в GitHub — задача не создана")
                    drop(op)
                } else {
                    Push.FAILED // забыли sha или кривой автор — наш баг, ретрай не поможет
                }
            else -> Push.RETRY
        }

    private fun diverged(op: WriteQueue.Op, fields: List<String>): Push {
        val named = fields.filter { it != TaskFile.STATUS_DONE || fields.size == 1 }
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

    private fun say(text: String) {
        cache.dir.mkdirs()
        File(cache.dir, DIVERGENCE).writeText(text)
    }

    private fun drop(op: WriteQueue.Op): Push {
        queue.done(op)
        return Push.MORE
    }

    private fun accept(path: String, entry: RepoCache.Entry, commitSha: String) {
        // Кэш обновляется из ответа записи: отдельный опрос ref не нужен (решение LLD-4).
        snapshot = snapshot.copy(commitSha = commitSha, files = snapshot.files + (path to entry))
        cache.save(repo, snapshot)
    }

    /** Кэш + журнал: то, что владелец видит на экране прямо сейчас. */
    private fun overlay(): Map<String, String> {
        val texts = LinkedHashMap<String, String>()
        snapshot.files.filterKeys { isTask(it) }.forEach { (path, e) -> texts[path] = e.text }
        queue.pending().filter { isTask(it.path) }.forEach { op ->
            when (val edit = op.edit) {
                Edit.DeleteFile -> texts.remove(op.path)
                is Edit.CreateTask -> texts[op.path] = edit.content
                else -> texts[op.path]?.let { texts[op.path] = Edit.apply(it, edit) }
            }
        }
        return texts
    }

    private fun sha(path: String): String? = snapshot.files[path]?.sha

    private fun isTask(path: String): Boolean =
        path.startsWith(TaskFile.DIR) && path.endsWith(".md")

    private fun message(op: WriteQueue.Op): String {
        val name = op.path.substringAfterLast('/')
        return when (op.edit) {
            is Edit.CreateTask -> "Новая задача $name"
            Edit.DeleteFile -> "Удалена задача $name"
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
        private const val DIVERGENCE = "divergence.txt"

        /** Кэш репо на телефоне — `files/repo/`. */
        fun cacheDir(filesDir: File): File = File(filesDir, "repo")
    }
}
