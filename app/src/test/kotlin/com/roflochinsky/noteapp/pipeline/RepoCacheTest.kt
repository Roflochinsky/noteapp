package com.roflochinsky.noteapp.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Контракт кэша (решение LLD-13): битое, чужое и старое переживается холодным стартом. */
class RepoCacheTest {

    @get:Rule val tmp = TemporaryFolder()

    private val repo = "Roflochinsky/voice-notes-test"

    private val snapshot =
        RepoCache.Snapshot(
            commitSha = "8026ade07cc707bbfb2c8059115962ef9fba5452",
            files =
                mapOf(
                    "tasks/2026-08-25-fix-retraev-ocheredi.md" to
                        RepoCache.Entry("fb52d8f", "---\ntitle: Фикс\n---\n"),
                    "встречи/2026-08-24-1807-reliz-tgsum.md" to
                        RepoCache.Entry("ef02ca8", "---\ntitle: Созвон\n---\n"),
                ),
        )

    @Test
    fun `сохранённое читается обратно`() {
        val cache = RepoCache(tmp.newFolder())
        cache.save(repo, snapshot)
        assertEquals(snapshot, cache.load(repo))
    }

    @Test
    fun `битый json — холодный старт, не исключение`() {
        val dir = tmp.newFolder()
        val cache = RepoCache(dir)
        cache.save(repo, snapshot)
        dir.listFiles()!!.first { it.name.endsWith(".json") }.writeText("{это не json")
        assertEquals(RepoCache.Snapshot(), cache.load(repo))
    }

    @Test
    fun `чужой репо не подсовывается`() {
        val cache = RepoCache(tmp.newFolder())
        cache.save(repo, snapshot)
        assertEquals(RepoCache.Snapshot(), cache.load("Roflochinsky/voice-notes"))
    }

    @Test
    fun `другая версия формата — холодный старт`() {
        val dir = tmp.newFolder()
        val cache = RepoCache(dir)
        cache.save(repo, snapshot)
        val file = dir.listFiles()!!.first { it.name.endsWith(".json") }
        file.writeText(file.readText().replace("\"version\":1", "\"version\":99"))
        assertEquals(RepoCache.Snapshot(), cache.load(repo))
    }

    @Test
    fun `пустой каталог — холодный старт`() {
        assertEquals(RepoCache.Snapshot(), RepoCache(tmp.newFolder()).load(repo))
    }

    @Test
    fun `запись не оставляет временных файлов`() {
        val dir = tmp.newFolder()
        val cache = RepoCache(dir)
        cache.save(repo, snapshot)
        cache.save(repo, snapshot.copy(commitSha = "другой"))
        val names = dir.listFiles()!!.map { it.name }
        assertEquals(names.toString(), 1, names.size)
        assertTrue(names.toString(), names.single().endsWith(".json"))
    }
}
