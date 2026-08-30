package com.roflochinsky.noteapp.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Заметки через тот же фасад, что и задачи (срез Н5, долг Н1): деталка больше не ходит в
 * `GithubClient` за файлом сама, а правка поля ложится в ту же очередь и видна поверх кэша сразу
 * (pending-overlay, решение LLD-5).
 */
class RepoNotesTest {

    @get:Rule val tmp = TemporaryFolder()

    private val meeting =
        """
        ---
        recorded: 2026-08-24T18:07:32+03:00
        duration: 12:31
        device: OnePlus 13
        type: встреча
        participants: [Дима]
        title: Созвон с Димой — релиз tgsum
        status: done
        ---

        ## Саммари
        **Суть.** Релиз в пятницу.
        """
            .trimIndent()

    private val path = "встречи/2026-08-24-1807-reliz-tgsum.md"

    private fun api() =
        FakeGithubApi().apply {
            put(path, meeting)
            put("inbox/2026-08-25-0910.md", "---\nrecorded: 2026-08-25T09:10:00+03:00\n---\n")
            put("tasks/2026-08-25-fix.md", "---\ntitle: Фикс\nstatus: open\n---")
            put("people.md", "- Дима — коллега\n- Оля — дизайн")
        }

    private fun store(api: GithubApi) = RepoStore(RepoCache(tmp.newFolder(), "r/test", "t"), api)

    @Test
    fun `обновление приносит заметки, а не только задачи`() {
        val store = store(api())
        assertEquals(SyncStatus.OK, store.refresh())
        val view = store.view()
        assertEquals(listOf("Фикс"), view.tasks.map { it.title })
        assertEquals(listOf("inbox/2026-08-25-0910.md", path), view.notes.map { it.path }.sorted())
    }

    @Test
    fun `реестр персон приезжает вместе с заметками`() {
        val store = store(api())
        store.refresh()
        assertEquals(listOf("Дима", "Оля"), store.view().people)
    }

    @Test
    fun `правка поля заметки видна сразу и уходит коммитом`() {
        val api = api()
        val store = store(api)
        store.refresh()
        store.edit(path, Edit.SetField("project", "tgsum"))

        val shown = store.view().notes.single { it.path == path }
        assertEquals("tgsum", shown.project)
        assertTrue("правка ждёт отправки", path in store.view().pending)

        assertEquals(RepoStore.Push.MORE, store.push())
        assertTrue("коммит ушёл в репо", api.text(path).orEmpty().contains("project: tgsum"))
        assertEquals(RepoStore.Push.EMPTY, store.push())
    }

    /** Правка участников — то же поле-список, что и в задачах: пишется одной строкой инлайном. */
    @Test
    fun `правка участников заметки пишется списком`() {
        val api = api()
        val store = store(api)
        store.refresh()
        store.edit(path, Edit.SetField("participants", Frontmatter.inline(listOf("Дима", "Оля"))))
        store.push()
        assertTrue(api.text(path).orEmpty().contains("participants: [Дима, Оля]"))
        assertEquals(
            listOf("Дима", "Оля"),
            store.view().notes.single { it.path == path }.participants,
        )
    }

    /**
     * Чужой файл в папке типа — без frontmatter, значит `NoteFile.parse` отдаёт `null`. Из ленты он
     * всё равно не пропадает: `NoteRef.merge` обещает «уходит вниз, но не теряется», а `mapNotNull`
     * в `view()` выбрасывал его молча (замечание ревью Н5).
     */
    @Test
    fun `заметка без frontmatter из ленты не пропадает`() {
        val api = api().apply { put("идеи/черновик.md", "просто текст, никакого frontmatter\n") }
        val store = store(api)
        store.refresh()
        assertTrue("идеи/черновик.md" in store.view().notes.map { it.path })
        val feed = NoteRef.merge(emptyList(), store.view().notes)
        assertEquals("строка без времени уходит вниз", "идеи/черновик.md", feed.last().path)
        assertEquals(3, feed.size)
    }

    /**
     * 409 по заметке (research §7). Пока правка ждала сети, Action дописал заметке другое поле:
     * правки не пересеклись — сливаем молча и переигрываем. Главное — тело заметки не пересобрано:
     * секции «Саммари» и «Транскрипт» те же посимвольно, транскрипт Action переписывать не вправе.
     */
    @Test
    fun `на 409 правка заметки сливается, а саммари и транскрипт целы`() {
        val api = api()
        val store = store(api)
        store.refresh()
        store.edit(path, Edit.SetField("project", "tgsum"))
        api.onWrite = {
            api.put(path, Edit.apply(meeting, Edit.SetField("tags", "[релиз]")))
            api.onWrite = null
        }
        assertEquals(RepoStore.Push.MORE, store.push()) // 409 → перечитали и слили
        assertEquals(RepoStore.Push.MORE, store.push()) // повтор на свежем sha
        assertEquals(RepoStore.Push.EMPTY, store.push())
        val text = checkNotNull(api.text(path))
        assertTrue(text, text.contains("project: tgsum"))
        assertTrue(text, text.contains("tags: [релиз]"))
        assertEquals(body(meeting), body(text))
        assertNull("расхождения быть не должно", store.view().notice)
    }

    /** То же поле с двух сторон — побеждает git, и владелец про это узнаёт (решение LLD-2). */
    @Test
    fun `на 409 по тому же полю заметки побеждает git и владелец это видит`() {
        val api = api()
        val store = store(api)
        store.refresh()
        store.edit(path, Edit.SetField("project", "tgsum"))
        api.onWrite = {
            api.put(path, Edit.apply(meeting, Edit.SetField("project", "workwatch")))
            api.onWrite = null
        }
        assertEquals(RepoStore.Push.MORE, store.push())
        assertEquals(RepoStore.Push.EMPTY, store.push())
        assertTrue(checkNotNull(api.text(path)).contains("project: workwatch"))
        val notice = store.view().notice
        assertNotNull(notice)
        assertTrue(notice!!, notice.contains("Проект") && notice.contains("GitHub"))
        assertEquals("workwatch", store.view().notes.single { it.path == path }.project)
    }

    /** Тело файла ниже frontmatter — то, что правка полей трогать не должна вовсе. */
    private fun body(md: String): String = md.substringAfter("---").substringAfter("---")

    /** Сообщение коммита говорит про заметку — по пути видно, что это не задача. */
    @Test
    fun `коммит правки заметки назван заметкой`() {
        val api = api()
        val messages = mutableListOf<String>()
        val spy =
            object : GithubApi by api {
                override fun putFile(
                    path: String,
                    content: String,
                    message: String,
                    sha: String?,
                ): Written {
                    messages += message
                    return api.putFile(path, content, message, sha)
                }
            }
        val store = store(spy)
        store.refresh()
        store.edit(path, Edit.SetField("type", "идея"))
        store.push()
        assertEquals(listOf("Правка заметки 2026-08-24-1807-reliz-tgsum.md"), messages)
    }
}
