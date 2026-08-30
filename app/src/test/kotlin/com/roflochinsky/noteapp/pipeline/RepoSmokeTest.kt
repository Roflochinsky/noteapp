package com.roflochinsky.noteapp.pipeline

import com.roflochinsky.noteapp.ui.TaskFilter
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Смоук против **тестового** репо `Roflochinsky/voice-notes-test` (боевой не трогаем). Токен — из
 * переменной окружения, в файлах его нет:
 * ```
 * NOTEAPP_SMOKE_TOKEN=$(gh auth token) ./gradlew testDebugUnitTest --tests '*RepoSmokeTest*'
 * ```
 *
 * Без переменной тест пропускается — обычный гейт остаётся офлайновым.
 */
class RepoSmokeTest {

    @get:Rule val tmp = TemporaryFolder()

    private val repo = "Roflochinsky/voice-notes-test"

    @Test
    fun `читает живой тестовый репо и разбирает задачи`() {
        val token = System.getenv("NOTEAPP_SMOKE_TOKEN").orEmpty()
        assumeTrue("нет NOTEAPP_SMOKE_TOKEN — смоук пропущен", token.isNotEmpty())
        val store = RepoStore(RepoCache(tmp.newFolder(), repo, token), GithubClient(repo, token))
        assertEquals(SyncStatus.OK, store.refresh())
        val today = LocalDate.now()
        val tasks = store.view().tasks
        println("СМОУК $repo · задач: ${tasks.size}")
        TaskFilter.byPriority(tasks, today).forEach { (priority, group) ->
            println("  $priority:")
            group.forEach { println("    ${line(it, today)}") }
        }
        println("  Сделано за месяц · ${TaskFilter.doneCount(tasks, today)}:")
        TaskFilter.done(tasks, today).forEach { println("    ${line(it, today)}") }
        val note = GithubClient(repo, token).readFile("встречи/2026-08-24-1807-reliz-tgsum.md")
        println("  заметка (кириллица в пути): ${NoteFile.parse("", note.text)?.title}")
    }

    /**
     * Демо среза Н7 на живом API: два обновления подряд без чужих коммитов. Второе уходит условным
     * запросом с `If-None-Match`, получает `304` — и счётчик `x-ratelimit-used` не двигается
     * (research §3.2). Счётчик снимается через `GET /rate_limit`, который сам основной лимит не
     * тратит, поэтому замер не искажает то, что меряет.
     */
    @Test
    fun `второе обновление живого репо не тратит квоту`() {
        val token = System.getenv("NOTEAPP_SMOKE_TOKEN").orEmpty()
        assumeTrue("нет NOTEAPP_SMOKE_TOKEN — смоук пропущен", token.isNotEmpty())
        val cache = RepoCache(tmp.newFolder(), repo, token)
        val store = RepoStore(cache, GithubClient(repo, token))

        val cold = used(token)
        assertEquals(SyncStatus.OK, store.refresh())
        val warm = used(token)
        val etag = cache.snapshot().etag
        println("СМОУК ETag · холодное чтение стоило ${warm - cold} запросов, ключ ветки: $etag")
        assertTrue("GitHub не дал ETag на git/ref", etag.isNotEmpty())

        assertEquals(SyncStatus.OK, store.refresh())
        val after = used(token)
        println("СМОУК ETag · x-ratelimit-used до второго обновления: $warm, после: $after")
        // Счётчик общий на весь токен (research §3.1): смоук соседнего дерева агента двигает его
        // нам под руку, и голое равенство было бы лотереей. Расхождение переспрашиваем ещё одним
        // таким же обновлением — два совпадения подряд на чужой трафик уже не спишешь.
        if (after != warm) {
            val again = cost(store, token)
            assertEquals("второе обновление обязано быть бесплатным ($warm → $after)", 0, again)
        }

        // Репо тестовое и общее: параллельный смоук записи мог сдвинуть ветку — тогда 200 честный,
        // и проверяем на свежем ключе. Второй раз подряд такое совпадение уже не случайность.
        val probe = GithubClient(repo, token).readRef(cache.snapshot().etag)
        assertNull("условный запрос обязан отдать 304", probe ?: retry(token, store, cache))
        println("СМОУК ETag · условный запрос вернул 304, запросов после первого чтения: 0")

        // Ветка сдвинулась чужим коммитом — в жизни это Action с саммари. Здесь ветку двигаем
        // своим файлом со штампом, а кэш возвращаем в то состояние, в котором приложение и
        // застаёт чужой коммит: «синхронизированы на прежнем коммите, нового текста не знаем».
        // Без этого отката проверять было бы нечего: после своего push кэш знает и коммит, и
        // текст (решение LLD-4), и обновление обходится одним запросом, минуя `compare`.
        val was = cache.snapshot()
        val path = store.create("Смоук дельты ${System.currentTimeMillis() % STAMP}")
        while (store.push() == RepoStore.Push.MORE) Unit
        cache.save(was.copy(files = was.files - path))
        val moved = used(token)
        assertEquals(SyncStatus.OK, store.refresh(force = true))
        val delta = used(token) - moved
        println("СМОУК дельты · обновление после чужого коммита стоило $delta запроса (было 10)")
        assertEquals(
            "опрос ветки + compare + ровно один блоб; счётчик общий на токен — чужой смоук в " +
                "соседнем дереве завышает эту цифру, тогда прогон повторить в одиночку",
            DELTA_BUDGET,
            delta,
        )
        val task = store.view().tasks.single { it.path == path }
        println("СМОУК дельты · дочитана только новая задача: ${task.title}")

        store.delete(path)
        while (store.push() == RepoStore.Push.MORE) Unit
        println("СМОУК дельты · $path удалён, репо в исходном состоянии")
    }

    /** Во сколько запросов основного лимита обошлось ещё одно обновление. */
    private fun cost(store: RepoStore, token: String): Int {
        val before = used(token)
        store.refresh()
        return used(token) - before
    }

    /** Ветку сдвинули между замерами — переспрашиваем один раз на свежем ключе. */
    private fun retry(token: String, store: RepoStore, cache: RepoCache): Ref? {
        store.refresh(force = true)
        return GithubClient(repo, token).readRef(cache.snapshot().etag)
    }

    /** `x-ratelimit-used` основного лимита; сам вызов его не тратит (research §3.1). */
    private fun used(token: String): Int {
        val conn = java.net.URL("https://api.github.com/rate_limit").openConnection()
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        val json = org.json.JSONObject(conn.getInputStream().bufferedReader().readText())
        return json.getJSONObject("resources").getJSONObject("core").getInt("used")
    }

    /**
     * Сквозной цикл среза Н2 против тестового репо: создать задачу → отметить сделанной → удалить.
     * Трогает только свой файл со штампом времени в имени; чужие файлы `tasks/` не читаются на
     * запись и не удаляются. После прогона репо возвращается в исходное состояние.
     */
    @Test
    fun `создаёт, правит и удаляет задачу в живом тестовом репо`() {
        val token = System.getenv("NOTEAPP_SMOKE_TOKEN").orEmpty()
        assumeTrue("нет NOTEAPP_SMOKE_TOKEN — смоук пропущен", token.isNotEmpty())
        val store = RepoStore(RepoCache(tmp.newFolder(), repo, token), GithubClient(repo, token))
        assertEquals(SyncStatus.OK, store.refresh())

        val stamp = System.currentTimeMillis() % STAMP
        val path = store.create("Смоук записи $stamp", project = "tgsum", priority = "P3")
        println("СМОУК записи · создаём $path")
        assertEquals(RepoStore.Push.MORE, store.push())
        assertEquals(RepoStore.Push.EMPTY, store.push())
        assertTrue("файл не появился", path in GithubClient(repo, token).let { tree(it) })

        store.setStatus(path, TaskFile.STATUS_DONE)
        store.edit(path, Edit.AddSubtask("проверить и убрать"))
        while (store.push() == RepoStore.Push.MORE) Unit
        val after = GithubClient(repo, token).readFile(path).text
        println("СМОУК записи · файл после правок:\n$after")
        assertTrue(after, after.contains("status: done"))
        assertTrue(after, after.contains("- [ ] проверить и убрать"))

        store.delete(path)
        assertEquals(RepoStore.Push.MORE, store.push())
        assertEquals(RepoStore.Push.EMPTY, store.push())
        assertFalse("файл остался в репо", path in tree(GithubClient(repo, token)))
        println("СМОУК записи · $path удалён, репо в исходном состоянии")
    }

    private fun tree(api: GithubApi): Set<String> = api.readTree(api.readRef()).keys

    private fun line(task: TaskFile.Task, today: LocalDate): String = buildString {
        append(if (task.isDone) "[x] " else "[ ] ")
        append(task.title)
        task.due?.let { append(" · срок $it") }
        if (TaskFilter.isOverdue(task, today)) append(" · ПРОСРОЧЕНО")
        if (task.status == TaskFile.STATUS_IN_PROGRESS) append(" · в работе")
        task.done?.let { append(" · закрыта $it") }
        task.project?.let { append(" · $it") }
        if (task.subtasks.isNotEmpty()) {
            append(" · подзадачи ${task.subtasks.count { it.done }}/${task.subtasks.size}")
        }
        append(" · ${task.path}")
    }

    private companion object {
        /** Хвост миллисекунд в имени файла — чтобы два прогона подряд не столкнулись. */
        const val STAMP = 1_000_000L

        /** Опрос ветки, один `compare` и ровно один блоб — против десяти запросов на всё дерево. */
        const val DELTA_BUDGET = 3
    }
}
