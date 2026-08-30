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

    private fun priority(store: RepoStore, title: String): String? =
        store.view().tasks.firstOrNull { it.title == title }?.priority

    private fun store(dir: java.io.File, api: GithubApi?) =
        RepoStore(RepoCache(dir, repo, "ghp_test"), api)

    @Test
    fun `обновление приносит задачи из репо`() {
        val store = store(tmp.newFolder(), api())
        assertEquals(SyncStatus.OK, store.refresh())
        val titles = store.view().tasks.map { it.title }.sorted()
        assertEquals(listOf("Разобрать фото с похода", "Фикс ретраев очереди"), titles)
    }

    @Test
    fun `задачи переживают перезапуск и видны без сети`() {
        val dir = tmp.newFolder()
        store(dir, api()).refresh()
        val cold = store(dir, null)
        assertEquals(2, cold.view().tasks.size)
        assertEquals(SyncStatus.NO_TOKEN, cold.refresh())
        assertEquals(2, cold.view().tasks.size)
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
        assertEquals(listOf("P3"), store.view().tasks.map { it.priority })
    }

    /**
     * Демо среза Н7: два обновления подряд без чужих коммитов. Второе уходит условным запросом,
     * получает 304 — и это единственный ответ GitHub, который не тратит квоту вообще (research
     * §3.2). Ни дерева, ни `compare`, ни блобов после него быть не должно.
     */
    @Test
    fun `второе обновление подряд не тратит квоту`() {
        val dir = tmp.newFolder()
        val api = api()
        val store = store(dir, api)
        assertEquals(SyncStatus.OK, store.refresh())
        val blobs = api.readBlobCalls
        val trees = api.readTreeCalls
        val refs = api.readRefCalls
        assertEquals(SyncStatus.OK, store.refresh())
        // Главное обещание среза: опрос ровно один. Без этой строки код, который получил 304 и
        // следом сходил за веткой платно, выглядел бы зелёным — все остальные счётчики он не
        // двигает.
        assertEquals("второе обновление — ровно один опрос ветки", refs + 1, api.readRefCalls)
        assertEquals("второй опрос обязан прийти 304", 1, api.notModifiedCalls)
        assertEquals("после 304 дерево не читают", trees, api.readTreeCalls)
        assertEquals("после 304 блобы не читают", blobs, api.readBlobCalls)
        assertEquals("после 304 сравнивать нечего", 0, api.compareCalls)
    }

    /** Ветка сдвинулась — разбор одним `compare`, дочитан ровно изменившийся файл. */
    @Test
    fun `после чужого коммита дочитывается только изменившийся файл`() {
        val dir = tmp.newFolder()
        val api = api()
        val store = store(dir, api)
        store.refresh()
        val blobs = api.readBlobCalls
        val trees = api.readTreeCalls
        api.put("tasks/2026-08-25-fix-retraev-ocheredi.md", fix.replace("P1", "P3"))
        assertEquals(SyncStatus.OK, store.refresh())
        assertEquals("разбор идёт через compare", 1, api.compareCalls)
        assertEquals("дерево при живом compare не нужно", trees, api.readTreeCalls)
        assertEquals("дочитан ровно один файл", blobs + 1, api.readBlobCalls)
        assertEquals("P3", priority(store, "Фикс ретраев очереди"))
    }

    /** Файл убрали чужим коммитом — уходит из списка через ту же дельту, без пересбора. */
    @Test
    fun `удалённый чужим коммитом файл пропадает по дельте`() {
        val dir = tmp.newFolder()
        val api = api()
        val store = store(dir, api)
        store.refresh()
        api.remove("tasks/2026-08-20-razobrat-foto.md")
        assertEquals(SyncStatus.OK, store.refresh())
        assertEquals(listOf("Фикс ретраев очереди"), store.view().tasks.map { it.title })
    }

    /**
     * Action переложил файл: текст тот же, blob-SHA тот же — читать нечего, запись просто
     * переезжает на новый путь. Так же выглядит и пара `removed` + `added`, которой GitHub иногда
     * заменяет `renamed` (вердикт HLD).
     */
    @Test
    fun `переименование не стоит ни одного чтения`() {
        val dir = tmp.newFolder()
        val api = api()
        val store = store(dir, api)
        store.refresh()
        val blobs = api.readBlobCalls
        api.remove("tasks/2026-08-25-fix-retraev-ocheredi.md")
        api.put("tasks/2026-08-25-fix-retraev-ocheredi-2.md", fix)
        assertEquals(SyncStatus.OK, store.refresh())
        assertEquals("текст берётся по blob-sha из кэша", blobs, api.readBlobCalls)
        assertEquals(
            listOf(
                "tasks/2026-08-20-razobrat-foto.md",
                "tasks/2026-08-25-fix-retraev-ocheredi-2.md",
            ),
            store.view().tasks.map { it.path }.sorted(),
        )
    }

    /**
     * `compare` картины не дал (усечение свыше 300 файлов или разошедшиеся ветки) — карта
     * пересобирается через `trees`, но тексты всё равно дочитываются только у изменившихся (решение
     * LLD-21).
     */
    @Test
    fun `усечённый compare — пересбор через дерево, дочитывается только изменившееся`() {
        val dir = tmp.newFolder()
        val api = api()
        val store = store(dir, api)
        store.refresh()
        val blobs = api.readBlobCalls
        val trees = api.readTreeCalls
        api.compareStale = true
        api.put("tasks/2026-08-25-fix-retraev-ocheredi.md", fix.replace("P1", "P3"))
        assertEquals(SyncStatus.OK, store.refresh())
        assertEquals("пересбор идёт через дерево", trees + 1, api.readTreeCalls)
        assertEquals("дочитан ровно один файл", blobs + 1, api.readBlobCalls)
        assertEquals("P3", priority(store, "Фикс ретраев очереди"))
    }

    /**
     * Ручное обновление — всегда безусловный запрос: поведение ETag на `git/ref` через CDN не
     * проверено, а владелец, дёрнувший экран вниз, обязан увидеть свежее состояние (граница Н7).
     */
    @Test
    fun `ручное обновление спрашивает репо безусловно`() {
        val dir = tmp.newFolder()
        val api = api()
        val store = store(dir, api)
        store.refresh()
        assertEquals(SyncStatus.OK, store.refresh(force = true))
        assertEquals("304 на ручном обновлении быть не должно", 0, api.notModifiedCalls)
        assertEquals(2, store.view().tasks.size)
    }

    @Test
    fun `нет сети — прежние задачи остаются`() {
        val dir = tmp.newFolder()
        val api = api()
        val store = store(dir, api)
        store.refresh()
        api.fail = UnknownHostException("api.github.com")
        assertEquals(SyncStatus.OFFLINE, store.refresh())
        assertEquals(2, store.view().tasks.size)
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

    @Test
    fun `не-IOException не роняет экран, а становится офлайном`() {
        val api = FakeGithubApi()
        api.put("tasks/a.md", "---\ntitle: A\n---\n")
        val store = RepoStore(RepoCache(tmp.newFolder(), "r/n", "ghp_test"), api)
        // 200 с не-JSON телом (кэптив-портал, HTML от CDN): org.json кидает JSONException, а она
        // IOException не родня — раньше это улетало из корутины и убивало приложение.
        api.fail = org.json.JSONException("A JSONObject text must begin with '{'")
        assertEquals(SyncStatus.OFFLINE, store.refresh())
    }

    /**
     * Значения чипа «Проект» приходят из `projects.md`, а не из того, что уже проставлено в
     * задачах: иначе проект без задач в шторке не покажется и счётчика 0 у него не будет (вердикт
     * UX, срез Н3).
     */
    @Test
    fun `реестр проектов приезжает обновлением и виден экрану`() {
        val dir = tmp.newFolder()
        val api = api()
        api.put("projects.md", "# Проекты\n\n- tgsum — суммаризатор\n- workwatch — трекер\n")
        val store = store(dir, api)
        store.refresh()
        assertEquals(listOf("tgsum", "workwatch"), store.view().projects)
        // Реестр — не задача: в список задач он не попадает.
        assertEquals(2, store.view().tasks.size)
        // И переживает перезапуск вместе с кэшем.
        assertEquals(listOf("tgsum", "workwatch"), store(dir, null).view().projects)
    }

    @Test
    fun `реестра в репо нет — список проектов пуст, экран не падает`() {
        val api = FakeGithubApi()
        api.put("tasks/a.md", "---\ntitle: A\n---\n")
        val store = RepoStore(RepoCache(tmp.newFolder(), repo, "ghp_test"), api)
        store.refresh()
        assertEquals(emptyList<String>(), store.view().projects)
    }

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_NOT_FOUND = 404
    }
}
