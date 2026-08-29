package com.roflochinsky.noteapp.pipeline

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Разовая миграция против **тестового** репо `Roflochinsky/voice-notes-test` (боевой не трогаем).
 *
 * Сухой прогон — только чтение, печатает полный список того, что изменится:
 * ```
 * NOTEAPP_SMOKE_TOKEN=$(gh auth token) bin/gate testDebugUnitTest --tests '*MigrationSmokeTest*'
 * ```
 *
 * Настоящий прогон пишет в репо и потому заперт своей переменной — обычный гейт его не запускает
 * никогда:
 * ```
 * NOTEAPP_SMOKE_TOKEN=$(gh auth token) NOTEAPP_MIGRATE=1 bin/gate testDebugUnitTest --tests '*MigrationSmokeTest*'
 * ```
 *
 * После настоящего прогона тест возвращает репо в исходное состояние обратной пачкой — тем же
 * жестом, что и `git revert` у владельца: содержимое сходится побайтово по blob-SHA всего дерева.
 */
class MigrationSmokeTest {

    private val repo = "Roflochinsky/voice-notes-test"

    private fun token(): String = System.getenv("NOTEAPP_SMOKE_TOKEN").orEmpty()

    /**
     * Шов записи для JVM-прогона. Боевой `httpSend` здесь не годится: движение ветки — это `PATCH`,
     * а `HttpURLConnection` в OpenJDK такой метод не принимает (`ProtocolException`). На Android он
     * проходит — там реализация на OkHttp, — поэтому подмена живёт только в смоуке и продакшна не
     * касается. `java.net.http.HttpClient` тоже не вариант: юнит-тесты Android-модуля компилируются
     * с `android.jar` в bootclasspath, и пакета `java.net.http` в нём просто нет.
     *
     * ponytail: значит, транспорт — внешний `curl`. Токен уходит файлом конфигурации (права 600), а
     * не в argv, где его видно в списке процессов.
     */
    private fun client(token: String) =
        GithubClient(repo, token, write = { method, url, body -> curl(method, url, body, token) })

    private fun curl(method: String, url: String, body: String, token: String): String {
        val config = File.createTempFile("noteapp-", ".curl")
        val data = File.createTempFile("noteapp-", ".json")
        try {
            config.writeText(
                """
                header = "Authorization: Bearer $token"
                header = "Accept: application/vnd.github+json"
                header = "Content-Type: application/json"
                """
                    .trimIndent()
            )
            data.writeText(body)
            val process =
                ProcessBuilder(
                        "curl",
                        "-sS",
                        "-X",
                        method,
                        "--config",
                        config.path,
                        "--data-binary",
                        "@${data.path}",
                        "-w",
                        "\n%{http_code}",
                        url,
                    )
                    .redirectErrorStream(true)
                    .start()
            val out = process.inputStream.bufferedReader().readText()
            process.waitFor()
            val code = out.substringAfterLast("\n").trim().toIntOrNull() ?: 0
            val payload = out.substringBeforeLast("\n")
            if (code !in SUCCESS) throw GithubHttpException(code, payload.take(PREVIEW))
            return payload
        } finally {
            config.delete()
            data.delete()
        }
    }

    /** Слепок репо: тексты нужны только заметкам, остальным путям хватает самого имени. */
    private fun snapshot(api: GithubApi): Map<String, String> {
        val tree = api.readTree(api.readRef())
        return tree.mapValues { (path, sha) ->
            if (Migration.isNote(path)) api.readBlob(sha) else ""
        }
    }

    /**
     * Отчёт перечисляет не только изменяемое: «осмотрено N, не тронуто M» — единственное, чем
     * владелец отличает «миграции нечего делать» от «прогон не дошёл до заметок».
     */
    private fun report(files: Map<String, String>, plan: Migration.Plan): String = buildString {
        appendLine("МИГРАЦИЯ · сухой прогон по $repo")
        plan.made
            .groupBy { it.note }
            .forEach { (note, made) ->
                appendLine("  заметка $note")
                made.forEach {
                    appendLine(
                        "    + ${it.path} · «${it.title}» · ${if (it.done) "done" else "open"}"
                    )
                    appendLine("    ~ строка-чекбокс становится ссылкой")
                }
            }
        plan.skipped.forEach { appendLine("  пропущена (нет даты): $it") }
        val notes = files.keys.count(Migration::isNote)
        val changed = plan.made.map { it.note }.distinct().size
        appendLine(
            "  осмотрено заметок $notes, изменяется $changed, пропущено ${plan.skipped.size}, " +
                "не тронуто ${notes - changed - plan.skipped.size}"
        )
        appendLine(
            "  итого: задач ${plan.made.size}, файлов в коммите ${plan.changes.size}, " +
                "коммит один: «${Migration.MESSAGE}»"
        )
    }

    @Test
    fun `сухой прогон показывает весь список того, что изменится`() {
        assumeTrue("нет NOTEAPP_SMOKE_TOKEN — смоук пропущен", token().isNotEmpty())
        val api = client(token())
        val files = snapshot(api)
        val plan = Migration.plan(files)
        print(report(files, plan))
        // В тестовом репо лежит ровно одна немигрированная заметка (идеи/2026-08-12-…), вторая
        // (встречи/…) уже со ссылками — она в план попасть не должна.
        assertTrue(plan.made.toString(), plan.made.all { it.note.startsWith("идеи/") })
        assertEquals(emptyList<String>(), plan.skipped)
    }

    @Test
    fun `настоящий прогон — один коммит, повтор пуст, обратная пачка возвращает репо`() {
        assumeTrue("нет NOTEAPP_SMOKE_TOKEN — смоук пропущен", token().isNotEmpty())
        assumeTrue(
            "нет NOTEAPP_MIGRATE=1 — пишущий прогон пропущен",
            System.getenv("NOTEAPP_MIGRATE") == "1",
        )
        val api = client(token())
        val before = api.readTree(api.readRef())
        val files = snapshot(api)
        val plan = Migration.plan(files)
        assumeTrue("в репо уже нечего мигрировать", !plan.isEmpty)
        print(report(files, plan))

        val written = api.commitBatch(plan.changes, Migration.MESSAGE)
        println("МИГРАЦИЯ · коммит ${written.commitSha}")
        val after = api.readTree(api.readRef())
        assertEquals(written.commitSha, api.readRef())
        plan.made.forEach { assertTrue("нет файла ${it.path}", it.path in after) }
        val note = api.readFile(plan.made.first().note).text
        assertFalse(note, note.contains("- [ ]"))

        // Повторный прогон на уже мигрированном репо не делает ничего — идемпотентность вживую.
        assertTrue("повтор что-то нашёл", Migration.plan(snapshot(api)).isEmpty)

        // Откат: та же пачка наизнанку — заметки возвращаются, созданные задачи удаляются.
        val back =
            files
                .filterKeys { path -> plan.changes.any { it.path == path } }
                .map { (path, text) -> BatchPlan.Put(path, text) } +
                plan.made.map { BatchPlan.Delete(it.path) }
        api.commitBatch(back, "revert: ${Migration.MESSAGE}")
        println("МИГРАЦИЯ · откат применён, репо возвращён")
        assertEquals(before, api.readTree(api.readRef()))
    }

    private companion object {
        val SUCCESS = 200..299
        const val PREVIEW = 300
    }
}
