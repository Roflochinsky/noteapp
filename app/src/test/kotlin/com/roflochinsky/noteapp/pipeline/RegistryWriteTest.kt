package com.roflochinsky.noteapp.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Проект, заведённый из шторки, едет в `projects.md` теми же рельсами, что и правки задач: `Edit` в
 * журнале очереди, отправка через фасад, кэш из ответа записи (`bd nikitatrubaev-0rk.23`). Реестр
 * при этом не задача: строки в нём независимы, сливать нечего, а `Registry.add` идемпотентен.
 */
class RegistryWriteTest {

    @get:Rule val tmp = TemporaryFolder()

    private val repo = "Roflochinsky/voice-notes-test"

    private val md = "# Проекты\n\n- tgsum — Telegram export → Markdown CLI\n- noteapp\n"

    private fun api() = FakeGithubApi().apply { put(Registry.PROJECTS, md) }

    private fun ready(api: GithubApi): RepoStore =
        RepoStore(RepoCache(tmp.newFolder(), repo, "token"), api).also { it.refresh() }

    @Test
    fun `новый проект уезжает в projects_md одним коммитом`() {
        val api = api()
        val store = ready(api)
        store.addProject("voicebox")
        assertEquals(RepoStore.Push.MORE, store.push())
        assertEquals(RepoStore.Push.EMPTY, store.push())
        assertEquals(md + "- voicebox\n", api.text(Registry.PROJECTS))
        assertEquals(1, api.writeCalls)
    }

    /** Проект виден в чипах сразу, ещё до коммита: иначе задача с ним не отфильтровалась бы. */
    @Test
    fun `новый проект виден в списке до отправки`() {
        val store = ready(api())
        store.addProject("voicebox")
        assertEquals(listOf("tgsum", "noteapp", "voicebox"), store.view().projects)
    }

    /**
     * Двое завели проекты одновременно. Строки реестра независимы — сливать нечего, поэтому 409 не
     * расхождение, а повод переиграть: доезжают оба.
     */
    @Test
    fun `гонка — чужой проект приехал первым, в реестре оба`() {
        val api = api()
        val store = ready(api)
        store.addProject("voicebox")
        api.onWrite = {
            api.onWrite = null
            api.put(Registry.PROJECTS, md + "- чужой\n")
        }
        assertEquals(RepoStore.Push.MORE, store.push())
        assertEquals(RepoStore.Push.MORE, store.push())
        assertEquals(RepoStore.Push.EMPTY, store.push())
        assertEquals(md + "- чужой\n- voicebox\n", api.text(Registry.PROJECTS))
    }

    /**
     * Тот же проект успели завести с другой стороны: дубликата и пустого коммита быть не должно.
     */
    @Test
    fun `гонка — тот же проект уже в реестре, второго коммита нет`() {
        val api = api()
        val store = ready(api)
        store.addProject("voicebox")
        api.onWrite = {
            api.onWrite = null
            api.put(Registry.PROJECTS, md + "- voicebox\n")
        }
        assertEquals(RepoStore.Push.MORE, store.push())
        assertEquals(RepoStore.Push.MORE, store.push())
        assertEquals(RepoStore.Push.EMPTY, store.push())
        assertEquals(md + "- voicebox\n", api.text(Registry.PROJECTS))
        assertEquals(1, api.writeCalls)
        assertEquals(listOf("tgsum", "noteapp", "voicebox"), store.view().projects)
    }

    /**
     * Реестр правят наперегонки, попытки кончились. Молчать нельзя: владелец видел имя в чипе и
     * считает, что оно записано, — плашка называет и проект, и файл.
     */
    @Test
    fun `запись не прошла — владелец узнаёт плашкой, а имя из чипов уходит`() {
        val api = api()
        val store = ready(api)
        store.addProject("voicebox")
        var i = 0
        api.onWrite = { api.put(Registry.PROJECTS, md + "- чужой-${i++}\n") }
        repeat(ConflictRule.MAX_REPLAYS + 1) { assertEquals(RepoStore.Push.MORE, store.push()) }
        assertEquals(RepoStore.Push.EMPTY, store.push())
        val view = store.view()
        val notice = requireNotNull(view.notice) { "владельцу не сказали, что проект не записан" }
        assertTrue(notice, notice.contains("voicebox") && notice.contains(Registry.PROJECTS))
        assertFalse("имя осталось в чипах, хотя его нет в реестре", "voicebox" in view.projects)
    }
}
