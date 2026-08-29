package com.roflochinsky.noteapp.pipeline

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разбор **реальных** ответов GitHub, снятых с `Roflochinsky/voice-notes-test` и лежащих в
 * `app/src/test/resources/github/`. Транспорт подменяется на границе (решение HLD: `HttpTransport`
 * вместо непроверяемого object) — сети в гейте нет, живое чтение остаётся за `RepoSmokeTest`.
 *
 * Чего здесь нет и почему: ответ `git/blobs` в фикстурах не снят, а сочинять json нельзя — разбор
 * `readBlob` пока держит только смоук; `compare-ahead.json` ждёт своего метода в срезе Н7.
 */
class GithubClientTest {

    private val repo = "Roflochinsky/voice-notes-test"
    private val commit = "8026ade07cc707bbfb2c8059115962ef9fba5452"
    private val asked = mutableListOf<String>()

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/github/$name.json")) { "нет фикстуры $name.json" }
            .readText()

    private fun client(answer: (String) -> String) =
        GithubClient(repo, "ghp_v-test-ne-uhodit") { url ->
            asked += url
            answer(url)
        }

    @Test
    fun `sha ветки берётся из настоящего ответа git-ref`() {
        assertEquals(commit, client { fixture("ref") }.readRef())
        assertEquals(listOf("https://api.github.com/repos/$repo/git/ref/heads/main"), asked)
    }

    @Test
    fun `дерево репо разбирается в карту путь-blobSha`() {
        val tree = client { fixture("trees-recursive") }.readTree(commit)
        assertEquals(
            "fb52d8f63b29094655138c567093200d4f226a84",
            tree["tasks/2026-08-25-fix-retraev-ocheredi.md"],
        )
        assertEquals(
            "ef02ca81034f0f3c9083476eba8b51643585c1ab",
            tree["встречи/2026-08-24-1807-reliz-tgsum.md"],
        )
        assertEquals(
            "https://api.github.com/repos/$repo/git/trees/$commit?recursive=1",
            asked.single(),
        )
    }

    @Test
    fun `папки в карту не попадают — только блобы`() {
        val tree = client { fixture("trees-recursive") }.readTree(commit)
        assertFalse(tree.toString(), tree.containsKey("tasks"))
        assertFalse(tree.toString(), tree.containsKey("встречи"))
        assertTrue(tree.toString(), tree.keys.all { it.endsWith(".md") })
    }

    /** Решение LLD-14: в репо есть кириллические папки, путь кодируется по одному месту на всё. */
    @Test
    fun `кириллица в пути уходит процентами UTF-8`() {
        val client = client { throw IOException("до сети дело не доходит") }
        runCatching { client.readFile("встречи/2026-08-24-1807-reliz-tgsum.md") }
        assertEquals(
            "https://api.github.com/repos/$repo/contents/" +
                "%D0%B2%D1%81%D1%82%D1%80%D0%B5%D1%87%D0%B8/2026-08-24-1807-reliz-tgsum.md",
            asked.single(),
        )
    }
}
