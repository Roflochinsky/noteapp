package com.roflochinsky.noteapp.pipeline

import java.net.UnknownHostException
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Запись через фасад: оптимистичный overlay, очередь, обновление кэша из ответа записи и штатная
 * ветка 409 (решения LLD-1, LLD-4, LLD-5, LLD-6). Экран про HTTP не знает.
 */
class RepoStoreWriteTest {

    @get:Rule val tmp = TemporaryFolder()

    private val repo = "Roflochinsky/voice-notes-test"
    private val path = "tasks/2026-08-25-fix-retraev-ocheredi.md"
    private val today = LocalDate.parse("2026-08-26")

    private val fix =
        """
        ---
        title: Фикс ретраев очереди
        project: tgsum
        priority: P1
        status: open
        created: 2026-08-25
        ---

        Описание из заметки.

        ## Подзадачи
        - [ ] Воспроизвести баг
        """
            .trimIndent()

    private fun api() = FakeGithubApi().apply { put(path, fix) }

    private fun store(dir: java.io.File, api: GithubApi?) =
        RepoStore(RepoCache(dir, repo, "token"), api, today = today)

    private fun ready(api: GithubApi?): RepoStore =
        store(tmp.newFolder(), api).also { it.refresh() }

    private fun task(store: RepoStore) = store.tasks().single { it.path == path }

    @Test
    fun `галочка видна сразу и без сети, в репо пока ничего`() {
        val api = api()
        val store = ready(api)
        store.setStatus(path, TaskFile.STATUS_DONE)
        assertTrue("галочка отскочила", task(store).isDone)
        assertEquals(today, task(store).done)
        assertTrue("путь должен ждать отправки", path in store.pendingPaths())
        assertEquals(0, api.writeCalls)
        assertFalse(api.text(path)!!.contains("status: done"))
    }

    @Test
    fun `отправка уходит одним PUT и кэш обновляется из ответа`() {
        val api = api()
        val store = ready(api)
        val refs = api.readRefCalls
        store.setStatus(path, TaskFile.STATUS_DONE)
        assertEquals(RepoStore.Push.MORE, store.push())
        assertEquals(RepoStore.Push.EMPTY, store.push())
        assertTrue(api.text(path)!!.contains("status: done"))
        assertEquals(1, api.writeCalls)
        assertEquals("отдельный опрос ref после записи не нужен", refs, api.readRefCalls)
        assertFalse(path in store.pendingPaths())
        assertTrue(task(store).isDone)
        // Кэш взял свежий sha из ответа: следующая правка уходит без 409.
        store.edit(path, Edit.SetField("priority", "P3"))
        assertEquals(RepoStore.Push.MORE, store.push())
        assertTrue(api.text(path)!!.contains("priority: P3"))
    }

    @Test
    fun `обновление не трогает путь, ждущий отправки`() {
        val api = api()
        val store = ready(api)
        store.edit(path, Edit.SetField("priority", "P3"))
        api.put("tasks/новая-чужая.md", "---\ntitle: Чужая\nstatus: open\n---")
        assertEquals(SyncStatus.OK, store.refresh())
        assertEquals("P3", task(store).priority)
        assertTrue(path in store.pendingPaths())
        assertEquals(2, store.tasks().size)
    }

    @Test
    fun `конфликт по разным полям переигрывается молча`() {
        val api = api()
        val store = ready(api)
        store.edit(path, Edit.SetField("priority", "P3"))
        api.onWrite = {
            api.put(path, Edit.apply(fix, Edit.SetField("due", "2026-08-30")))
            api.onWrite = null
        }
        assertEquals(RepoStore.Push.MORE, store.push()) // 409 → перечитали и слили
        assertEquals(RepoStore.Push.MORE, store.push()) // повтор на свежем sha
        assertEquals(RepoStore.Push.EMPTY, store.push())
        val text = api.text(path)!!
        assertTrue(text, text.contains("priority: P3"))
        assertTrue(text, text.contains("due: 2026-08-30"))
        assertNull("расхождения быть не должно", store.takeDivergence())
    }

    @Test
    fun `конфликт по тому же полю оставляет значение git и говорит владельцу`() {
        val api = api()
        val store = ready(api)
        store.setStatus(path, TaskFile.STATUS_DONE)
        api.onWrite = {
            api.put(path, Edit.apply(fix, Edit.SetField("status", TaskFile.STATUS_IN_PROGRESS)))
            api.onWrite = null
        }
        assertEquals(RepoStore.Push.MORE, store.push())
        assertEquals(RepoStore.Push.EMPTY, store.push())
        assertTrue(api.text(path)!!.contains("status: in_progress"))
        val message = store.takeDivergence()
        assertNotNull(message)
        assertTrue(message!!, message.contains("Статус"))
        assertTrue(message, message.contains("GitHub"))
        assertEquals(TaskFile.STATUS_IN_PROGRESS, task(store).status)
        assertNull("сообщение показывается один раз", store.takeDivergence())
    }

    @Test
    fun `предел повторов останавливает переигрывание`() {
        val api = api()
        val store = ready(api)
        store.edit(path, Edit.SetField("priority", "P3"))
        var n = 0
        api.onWrite = {
            api.put(path, Edit.apply(api.text(path)!!, Edit.AddSubtask("чужая ${n++}")))
        }
        repeat(ConflictRule.MAX_REPLAYS * 2) { store.push() }
        assertEquals(RepoStore.Push.EMPTY, store.push())
        assertFalse("операция должна была уйти из очереди", path in store.pendingPaths())
        assertTrue(api.writeCalls <= ConflictRule.MAX_REPLAYS + 1)
    }

    @Test
    fun `создание кладёт файл, удаление его убирает`() {
        val api = api()
        val store = ready(api)
        val created = store.create("Купить переходник USB-C", priority = "P2")
        assertTrue("создание видно сразу", store.tasks().any { it.path == created })
        assertEquals(RepoStore.Push.MORE, store.push())
        assertTrue(api.paths().contains(created))
        assertTrue(api.text(created)!!.contains("status: open"))
        assertTrue(api.text(created)!!.contains("priority: P2"))
        store.delete(created)
        assertFalse("удаление видно сразу", store.tasks().any { it.path == created })
        assertEquals(RepoStore.Push.MORE, store.push())
        assertFalse(api.paths().contains(created))
        assertEquals(RepoStore.Push.EMPTY, store.push())
    }

    @Test
    fun `без сети операция остаётся в журнале и уходит позже`() {
        val dir = tmp.newFolder()
        val api = api()
        val store = store(dir, api).also { it.refresh() }
        store.setStatus(path, TaskFile.STATUS_DONE)
        api.fail = UnknownHostException("api.github.com")
        assertEquals(RepoStore.Push.RETRY, store.push())
        api.fail = null
        val revived = store(dir, api)
        assertTrue("операция потерялась при перезапуске", path in revived.pendingPaths())
        assertTrue(revived.tasks().single { it.path == path }.isDone)
        assertEquals(RepoStore.Push.MORE, revived.push())
        assertTrue(api.text(path)!!.contains("status: done"))
    }

    @Test
    fun `отмена снекбаром не оставляет коммита`() {
        val api = api()
        val store = ready(api)
        val id = store.setStatus(path, TaskFile.STATUS_DONE)
        store.cancel(id)
        assertFalse(task(store).isDone)
        assertEquals(RepoStore.Push.EMPTY, store.push())
        assertEquals(0, api.writeCalls)
    }

    @Test
    fun `подзадача и заголовок правятся тем же путём`() {
        val api = api()
        val store = ready(api)
        store.edit(path, Edit.ToggleSubtask("Воспроизвести баг", true))
        store.edit(path, Edit.SetTitle("Фикс ретраев очереди v2"))
        store.edit(path, Edit.AddSubtask("Тест на потерю сети"))
        assertEquals(RepoStore.Push.MORE, store.push())
        assertEquals(RepoStore.Push.MORE, store.push())
        assertEquals(RepoStore.Push.MORE, store.push())
        assertEquals(RepoStore.Push.EMPTY, store.push())
        val text = api.text(path)!!
        assertTrue(text, text.contains("title: Фикс ретраев очереди v2"))
        assertTrue(text, text.contains("- [x] Воспроизвести баг"))
        assertTrue(text, text.contains("- [ ] Тест на потерю сети"))
        assertTrue("тело файла пересобрано", text.contains("Описание из заметки."))
    }
}
