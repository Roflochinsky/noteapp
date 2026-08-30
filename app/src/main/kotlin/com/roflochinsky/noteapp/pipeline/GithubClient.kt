package com.roflochinsky.noteapp.pipeline

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Base64
import org.json.JSONObject

/**
 * Боевой адаптер порта [GithubApi] поверх GitHub REST (research
 * `docs/research/github-sync-api.md`). Base64 и кодирование пути — по одному месту на всё (решение
 * LLD-14): в репо есть кириллические папки `встречи/`, `идеи/`, а `android.util.Base64` в
 * JVM-юнитах — стаб.
 *
 * @param repo вида "owner/name".
 * @param fetch транспорт GET одной строкой: в бою — `HttpURLConnection`, в тестах — снятые с репо
 *   фикстуры. Разбор ответа так проверяется без сети (решение HLD про `HttpTransport`).
 * @param write тот же шов для мутаций (метод, URL, тело): без него цепочка батча — пять запросов и
 *   пересборка на 422 — проверялась бы только живой сетью.
 * @param conditional шов условного GET (URL и наш ETag): отдельный от [fetch], потому что здесь
 *   важны заголовки в обе стороны и код 304, а не только тело.
 */
class GithubClient(
    private val repo: String,
    private val token: String,
    private val fetch: (String) -> String = { url -> httpGet(url, token) },
    private val write: (String, String, String) -> String = { method, url, body ->
        httpSend(method, url, body, token)
    },
    private val conditional: (String, String?) -> Fetched? = { url, etag ->
        httpGetIfChanged(url, etag, token)
    },
) : GithubApi {

    /**
     * Ветку читает только этот метод — безусловный вызов (батч, `findDonePath`) отличается от
     * поллинга ровно пустым [etag], а не вторым путём в бою.
     */
    override fun readRef(etag: String?): Ref? =
        conditional("$API/$repo/git/ref/heads/main", etag)?.let {
            Ref(JSONObject(it.body).getJSONObject("object").getString("sha"), it.etag)
        }

    /** Голые SHA `compare` принимает — проверено живым запросом (research §6.C). */
    override fun compare(base: String, head: String): RepoDelta =
        RepoDelta.parse(fetch("$API/$repo/compare/$base...$head"))

    override fun readTree(commitSha: String): Map<String, String> {
        val tree =
            JSONObject(fetch("$API/$repo/git/trees/$commitSha?recursive=1")).getJSONArray("tree")
        return (0 until tree.length())
            .map { tree.getJSONObject(it) }
            .filter { it.getString("type") == "blob" }
            .associate { it.getString("path") to it.getString("sha") }
    }

    override fun readBlob(sha: String): String =
        decode(JSONObject(fetch("$API/$repo/git/blobs/$sha")).getString("content"))

    override fun readFile(path: String): RepoCache.Entry {
        val json = JSONObject(fetch("$API/$repo/contents/${encodePath(path)}"))
        return RepoCache.Entry(json.getString("sha"), decode(json.getString("content")))
    }

    /** Один PUT — один коммит. Без `sha` — только создание нового файла (решение LLD-8). */
    override fun putFile(path: String, content: String, message: String, sha: String?): Written {
        val body =
            JSONObject()
                .put("message", message)
                .put("content", Base64.getEncoder().encodeToString(content.toByteArray()))
        sha?.let { body.put("sha", it) }
        return written(write("PUT", contents(repo, path), body.toString()))
    }

    override fun deleteFile(path: String, message: String, sha: String): Written {
        val body = JSONObject().put("message", message).put("sha", sha)
        return written(write("DELETE", contents(repo, path), body.toString()))
    }

    /**
     * Пять запросов на любую пачку: ref → базовое дерево → новое дерево → коммит → движение ветки.
     *
     * `force: false` отклоняет коммит, если ветка успела уйти вперёд (Action записал саммари) —
     * тогда пачка пересобирается на новом HEAD **один** раз ([REF_REFUSED]). Второй отказ уходит
     * наружу исключением: бесконечно догонять чужие коммиты миграция не должна, а `force: true` в
     * noteapp не бывает никогда.
     */
    override fun commitBatch(changes: List<BatchPlan.Change>, message: String): Written {
        if (changes.isEmpty()) return Written(null, "")
        repeat(ATTEMPTS) { attempt ->
            val head = readRef()
            val base = JSONObject(fetch("$API/$repo/git/commits/$head")).getJSONObject("tree")
            val tree = post("git/trees", BatchPlan.tree(base.getString("sha"), changes))
            val commit = post("git/commits", BatchPlan.commit(message, tree, head))
            try {
                write("PATCH", "$API/$repo/git/refs/heads/main", BatchPlan.ref(commit).toString())
                return Written(null, commit)
            } catch (e: GithubHttpException) {
                if (e.code !in REF_REFUSED || attempt == ATTEMPTS - 1) throw e
            }
        }
        error("недостижимо: цикл возвращает или бросает")
    }

    /** Объекты git создаются POST-ом и отвечают своим SHA — только он нам и нужен. */
    private fun post(endpoint: String, body: JSONObject): String =
        JSONObject(write("POST", "$API/$repo/$endpoint", body.toString())).getString("sha")

    private fun written(response: String): Written {
        val json = JSONObject(response)
        return Written(
            sha = json.optJSONObject("content")?.optString("sha")?.takeIf { it.isNotEmpty() },
            commitSha = json.getJSONObject("commit").getString("sha"),
        )
    }

    private companion object {
        const val API = "https://api.github.com/repos"

        /** Ответы GitHub приходят с переносами строк; кодировка задана явно — в репо кириллица. */
        fun decode(base64: String): String =
            String(Base64.getMimeDecoder().decode(base64), Charsets.UTF_8)

        const val TIMEOUT_MS = 60_000
        const val ERR_PREVIEW = 300
        const val HTTP_NOT_MODIFIED = 304

        /**
         * Отказ `PATCH ref` с `force: false`. Research перечисляет у него 200, 409 и 422; оба
         * отказа для нас одно и то же — ветка не там, где мы её оставили, значит пересобрать пачку
         * на свежем HEAD. Различать их незачем: лечение общее.
         */
        val REF_REFUSED = setOf(409, 422)

        /** Попытка плюс ровно одна пересборка на 422 — больше миграция не догоняет. */
        const val ATTEMPTS = 2
        val SUCCESS_RANGE = 200..299

        /**
         * Условный GET: наш ETag уходит заголовком `If-None-Match`, ответ `304` возвращается как
         * `null`. Проверку кода [check] здесь звать нельзя — она бы приняла 304 за отказ, хотя это
         * штатный и самый желанный ответ поллинга (research §3.2).
         */
        @Throws(IOException::class)
        fun httpGetIfChanged(url: String, etag: String?, token: String): Fetched? {
            val conn = open(url, token)
            try {
                etag
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { conn.setRequestProperty("If-None-Match", it) }
                if (conn.responseCode == HTTP_NOT_MODIFIED) return null
                check(conn)
                return Fetched(
                    conn.inputStream.bufferedReader().readText(),
                    conn.getHeaderField("ETag"),
                )
            } finally {
                conn.disconnect()
            }
        }

        @Throws(IOException::class)
        fun httpGet(url: String, token: String): String {
            val conn = open(url, token)
            try {
                check(conn)
                return conn.inputStream.bufferedReader().readText()
            } finally {
                conn.disconnect()
            }
        }

        /**
         * Мутация с телом: тот же путь, что был у одиночного PUT, — общий на contents и git data.
         *
         * **PATCH и JVM.** `HttpURLConnection` в OpenJDK метод `PATCH` не принимает
         * (`ProtocolException`), реализация Android (OkHttp) — принимает. Боевой путь приложения —
         * Android, а JVM-прогоны (смоук, разовая миграция) подставляют свой шов [write] поверх
         * `java.net.http.HttpClient`. Проверить андроидную ветку офлайн нечем — на устройстве её
         * первым тронет срез Н6 (`bd nikitatrubaev-0rk.24`).
         */
        @Throws(IOException::class)
        fun httpSend(method: String, url: String, body: String, token: String): String {
            val conn = open(url, token)
            try {
                conn.requestMethod = method
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
                check(conn)
                return conn.inputStream.bufferedReader().readText()
            } finally {
                conn.disconnect()
            }
        }

        fun open(url: String, token: String): HttpURLConnection {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            return conn
        }

        @Throws(IOException::class)
        fun check(conn: HttpURLConnection) {
            val code = conn.responseCode
            if (code !in SUCCESS_RANGE) {
                val err =
                    conn.errorStream?.bufferedReader()?.readText()?.take(ERR_PREVIEW).orEmpty()
                throw GithubHttpException(code, "GitHub HTTP $code: $err")
            }
        }

        /** Единственный на всё приложение способ назвать файл репо в contents API. */
        fun contents(repo: String, path: String): String = "$API/$repo/contents/${encodePath(path)}"

        /** Пробел в пути — `%20`, не `+`; кириллица — UTF-8-проценты. */
        fun encodePath(path: String): String =
            path.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
    }
}

/** Ответ условного GET: тело и свежий ETag на следующий раз. `null` вместо него — ответ 304. */
data class Fetched(val body: String, val etag: String?)
