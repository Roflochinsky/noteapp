package com.roflochinsky.noteapp.pipeline

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Контракт кэша (решения LLD-13 и LLD-23): битое, чужое, старое и прочитанное другим токеном
 * переживается холодным стартом.
 */
class RepoCacheTest {

    @get:Rule val tmp = TemporaryFolder()

    /** Повторы и «жирный» снимок: рвущаяся запись должна успеть попасть в окно другой. */
    private val races = 6000
    private val fat = 400

    private val repo = "Roflochinsky/voice-notes-test"
    private val token = "ghp_stary1Token00000000000000000000000000"

    private fun cache(dir: File, repo: String = this.repo, token: String? = this.token) =
        RepoCache(dir, repo, token)

    private fun json(dir: File) = dir.listFiles()!!.first { it.name.endsWith(".json") }

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
        val cache = cache(tmp.newFolder())
        cache.save(snapshot)
        assertEquals(snapshot, cache.load())
    }

    @Test
    fun `битый json — холодный старт, не исключение`() {
        val dir = tmp.newFolder()
        cache(dir).save(snapshot)
        json(dir).writeText("{это не json")
        assertEquals(RepoCache.Snapshot(), cache(dir).load())
    }

    @Test
    fun `чужой репо не подсовывается`() {
        val dir = tmp.newFolder()
        cache(dir).save(snapshot)
        assertEquals(RepoCache.Snapshot(), cache(dir, repo = "Roflochinsky/voice-notes").load())
    }

    @Test
    fun `снимок из-под отозванного токена не отдаётся новому`() {
        val dir = tmp.newFolder()
        cache(dir).save(snapshot)
        val fresh = cache(dir, token = "ghp_novyi2Token00000000000000000000000000")
        assertEquals(RepoCache.Snapshot(), fresh.load())
    }

    @Test
    fun `токен убрали из настроек — снимок тоже не отдаётся`() {
        val dir = tmp.newFolder()
        cache(dir).save(snapshot)
        assertEquals(RepoCache.Snapshot(), cache(dir, token = null).load())
    }

    @Test
    fun `сам токен в файл кэша не попадает`() {
        val dir = tmp.newFolder()
        cache(dir).save(snapshot)
        val text = json(dir).readText()
        assertFalse(text, text.contains(token))
    }

    @Test
    fun `другая версия формата — холодный старт`() {
        val dir = tmp.newFolder()
        cache(dir).save(snapshot)
        json(dir).let { it.writeText(it.readText().replace("\"version\":1", "\"version\":99")) }
        assertEquals(RepoCache.Snapshot(), cache(dir).load())
    }

    @Test
    fun `пустой каталог — холодный старт`() {
        assertEquals(RepoCache.Snapshot(), cache(tmp.newFolder()).load())
    }

    /**
     * Смена токена, пока воркер тянет очередь: экран уже на новом фасаде, а воркер дописывает свой
     * `doWork()` на старом — два экземпляра кэша над одним каталогом и одним `repo.json.tmp`.
     *
     * Гонка редкая — ловим её повтором и барьером, а не сном в боевом коде.
     */
    @Test
    fun `два экземпляра кэша над одним каталогом не мешают друг другу`() {
        val dir = tmp.newFolder()
        val big =
            snapshot.copy(
                files = snapshot.files.mapValues { (_, e) -> e.copy(text = e.text.repeat(fat)) }
            )
        val gate = java.util.concurrent.CyclicBarrier(2)
        val boom = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val threads =
            listOf(cache(dir) to snapshot, cache(dir) to big).map { (cache, what) ->
                Thread {
                    repeat(races) {
                        gate.await()
                        runCatching { cache.save(what) }.onFailure(boom::set)
                    }
                }
            }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals("запись кэша не должна падать: ${boom.get()}", null, boom.get())
        assertTrue("кэш ушёл в холодный старт", cache(dir).load().files.isNotEmpty())
    }

    @Test
    fun `запись не оставляет временных файлов`() {
        val dir = tmp.newFolder()
        val cache = cache(dir)
        cache.save(snapshot)
        cache.save(snapshot.copy(commitSha = "другой"))
        val names = dir.listFiles()!!.map { it.name }
        assertEquals(names.toString(), 1, names.size)
        assertTrue(names.toString(), names.single().endsWith(".json"))
    }
}
