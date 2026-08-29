package com.roflochinsky.noteapp.pipeline

import com.roflochinsky.noteapp.ui.TaskFilter
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val tasks = store.tasks()
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
    }
}
