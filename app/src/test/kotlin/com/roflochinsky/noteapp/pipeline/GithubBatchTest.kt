package com.roflochinsky.noteapp.pipeline

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Батч-коммит против **настоящих** ответов GitHub, снятых с `Roflochinsky/voice-notes-test`
 * (`app/src/test/resources/github/`): `git/commits/{sha}` — обычный GET, а `git-trees-post.json` и
 * `git-commits-post.json` сняты с реальных `POST /git/trees` и `POST /git/commits` — оба создают
 * висячие объекты и ветку не двигают, поэтому репо от их снятия не изменился (проверено: `head` до
 * и после совпал). Сочинять json нельзя, и здесь ничего не сочинено.
 *
 * Ответ `PATCH ref` клиент не разбирает вовсе (SHA коммита уже известен), поэтому шов отдаёт на
 * него пустое тело, а отказ ветки — тем же `GithubHttpException(422)`, каким его кидает боевой
 * `check()`.
 */
class GithubBatchTest {

    private val repo = "Roflochinsky/voice-notes-test"
    private val head = "8026ade07cc707bbfb2c8059115962ef9fba5452"
    private val baseTree = "90d1d4f402da699ace8e2ac31ed97437cd753863"
    private val newTree = "2e468480eb7e1fa05826e07aa6a4f56921d3f4af"
    private val newCommit = "8a890a0775381a32d3e57dcc7e5623744b132178"
    private val changes = listOf(BatchPlan.Put("tasks/2026-08-12-probe-batch.md", "текст"))

    private val got = mutableListOf<String>()
    private val bodies = mutableListOf<String>()
    private var patches = 0

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/github/$name.json")) { "нет фикстуры $name" }
            .readText()

    private fun get(url: String): String {
        got += url
        return when {
            url.endsWith("git/ref/heads/main") -> fixture("ref")
            url.contains("git/commits/") -> fixture("git-commit")
            else -> error("неожиданный GET $url")
        }
    }

    private fun client(refuseRef: Int = 0, code: Int = 422) =
        GithubClient(
            repo,
            "ghp_v-test-ne-uhodit",
            fetch = ::get,
            // Ветку батч читает безусловно, но тем же швом, что и поллинг: пустой ETag.
            conditional = { url, _ -> Fetched(get(url), null) },
            write = { method, url, body ->
                got += "$method $url"
                bodies += body
                when {
                    url.endsWith("git/trees") -> fixture("git-trees-post")
                    url.endsWith("git/commits") -> fixture("git-commits-post")
                    else -> {
                        patches++
                        if (patches <= refuseRef) {
                            throw GithubHttpException(code, "Update is not a fast forward")
                        }
                        ""
                    }
                }
            },
        )

    private fun body(n: Int) = JSONObject(bodies[n])

    @Test
    fun `пачка уходит одним коммитом — дерево поверх base_tree, родитель HEAD, без force`() {
        assertEquals(Written(null, newCommit), client().commitBatch(changes, Migration.MESSAGE))
        assertEquals(
            listOf(
                "https://api.github.com/repos/$repo/git/ref/heads/main",
                "https://api.github.com/repos/$repo/git/commits/$head",
                "POST https://api.github.com/repos/$repo/git/trees",
                "POST https://api.github.com/repos/$repo/git/commits",
                "PATCH https://api.github.com/repos/$repo/git/refs/heads/main",
            ),
            got,
        )
        assertEquals(baseTree, body(0).getString("base_tree"))
        assertEquals(
            "tasks/2026-08-12-probe-batch.md",
            body(0).getJSONArray("tree").getJSONObject(0).getString("path"),
        )
        assertEquals(Migration.MESSAGE, body(1).getString("message"))
        assertEquals(newTree, body(1).getString("tree"))
        assertEquals(head, body(1).getJSONArray("parents").getString(0))
        assertEquals(newCommit, body(2).getString("sha"))
        assertFalse(body(2).getBoolean("force"))
    }

    @Test
    fun `ветка ушла вперёд — пачка пересобирается на новом HEAD ровно один раз`() {
        assertEquals(
            newCommit,
            client(refuseRef = 1).commitBatch(changes, Migration.MESSAGE).commitSha,
        )
        assertEquals(2, got.count { it.endsWith("git/ref/heads/main") })
        assertEquals(2, got.count { it.startsWith("POST") && it.endsWith("git/trees") })
        assertEquals(2, patches)
    }

    @Test
    fun `второй отказ ветки наружу — третьей попытки нет`() {
        val e =
            assertThrows(GithubHttpException::class.java) {
                client(refuseRef = 2).commitBatch(changes, Migration.MESSAGE)
            }
        assertEquals(422, e.code)
        assertEquals(2, patches)
    }

    /**
     * Research (`docs/research/github-sync-api.md`, «`force: false` — это защита от гонки»)
     * перечисляет у `PATCH ref` коды 200, 409 и 422. Оба отказа означают одно: HEAD сдвинулся или
     * ветка недоступна — пересобрать пачку на свежем HEAD, а не падать.
     */
    @Test
    fun `отказ ветки кодом 409 — та же пересборка, что и на 422`() {
        assertEquals(
            newCommit,
            client(refuseRef = 1, code = 409).commitBatch(changes, Migration.MESSAGE).commitSha,
        )
        assertEquals(2, patches)
    }

    @Test
    fun `чужой код наружу без пересборки — это не гонка ветки`() {
        val e =
            assertThrows(GithubHttpException::class.java) {
                client(refuseRef = 1, code = 404).commitBatch(changes, Migration.MESSAGE)
            }
        assertEquals(404, e.code)
        assertEquals(1, patches)
    }

    @Test
    fun `пустой пачке коммит не нужен — в GitHub не ходим вовсе`() {
        assertEquals(Written(null, ""), client().commitBatch(emptyList(), Migration.MESSAGE))
        assertTrue(got.toString(), got.isEmpty())
    }
}
