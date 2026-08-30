package com.roflochinsky.noteapp.pipeline

import org.junit.Assert.assertEquals
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
