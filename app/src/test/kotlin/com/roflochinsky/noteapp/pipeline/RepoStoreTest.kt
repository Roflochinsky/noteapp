package com.roflochinsky.noteapp.pipeline

import java.io.IOException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Фасад для UI: только чтение (срез Н1). UI не знает ни про кэш, ни про API. */
class RepoStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private val repo = "Roflochinsky/voice-notes-test"

    private val fix =
        """
        ---
        title: Фикс ретраев очереди
        priority: P1
        status: open
        created: 2026-08-25
        ---
        """
            .trimIndent()

    private val foto =
        """
        ---
        title: Разобрать фото с похода
        priority: P3
        status: done
        created: 2026-08-20
        ---
        """
            .trimIndent()

    private fun api() =
        FakeGithubApi().apply {
            put("tasks/2026-08-25-fix-retraev-ocheredi.md", fix)
            put("tasks/2026-08-20-razobrat-foto.md", foto)
            put("встречи/2026-08-24-1807-reliz-tgsum.md", "---\ntitle: Созвон\nstatus: done\n---")
            put("projects.md", "- tgsum — суммаризатор")
        }

    private fun store(dir: java.io.File, api: GithubApi?) = RepoStore(repo, RepoCache(dir), api)

    @Test
    fun `обновление приносит задачи из репо`() {
        val store = store(tmp.newFolder(), api())
        assertEquals(SyncStatus.OK, store.refresh())
        val titles = store.tasks().map { it.title }.sorted()
        assertEquals(listOf("Разобрать фото с похода", "Фикс ретраев очереди"), titles)
    }

    @Test
    fun `задачи переживают перезапуск и видны без сети`() {
        val dir = tmp.newFolder()
        store(dir, api()).refresh()
        val cold = store(dir, null)
        assertEquals(2, cold.tasks().size)
        assertEquals(SyncStatus.NO_TOKEN, cold.refresh())
        assertEquals(2, cold.tasks().size)
    }

    @Test
    fun `неизменившиеся файлы повторно не скачиваются`() {
        val dir = tmp.newFolder()
        val api = api()
        val store = store(dir, api)
        store.refresh()
        val first = api.readBlobCalls
        assertTrue("первый проход должен читать блобы", first >= 2)
        store.refresh()
        assertEquals(first, api.readBlobCalls)
    }

    @Test
    fun `изменившийся файл дочитывается, удалённый пропадает`() {
        val dir = tmp.newFolder()
        val api = api()
        val store = store(dir, api)
        store.refresh()
        api.put("tasks/2026-08-25-fix-retraev-ocheredi.md", fix.replace("P1", "P3"))
        api.remove("tasks/2026-08-20-razobrat-foto.md")
        store.refresh()
        assertEquals(listOf("P3"), store.tasks().map { it.priority })
    }

    @Test
    fun `нет сети — прежние задачи остаются`() {
        val dir = tmp.newFolder()
        val api = api()
        val store = store(dir, api)
        store.refresh()
        api.fail = UnknownHostException("api.github.com")
        assertEquals(SyncStatus.OFFLINE, store.refresh())
        assertEquals(2, store.tasks().size)
    }

    @Test
    fun `ошибки доступа и лимита различаются`() {
        val api = api()
        val store = store(tmp.newFolder(), api)
        api.fail = GithubHttpException(HTTP_UNAUTHORIZED, "bad token")
        assertEquals(SyncStatus.NO_ACCESS, store.refresh())
        api.fail = GithubHttpException(HTTP_NOT_FOUND, "not found")
        assertEquals(SyncStatus.NO_ACCESS, store.refresh())
        api.fail = GithubHttpException(HTTP_FORBIDDEN, "rate limit exceeded")
        assertEquals(SyncStatus.RATE_LIMIT, store.refresh())
        api.fail = IOException("timeout")
        assertEquals(SyncStatus.OFFLINE, store.refresh())
    }

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_NOT_FOUND = 404
    }
}
