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
 */
class GithubClient(
    private val repo: String,
    private val token: String,
    private val fetch: (String) -> String = { url -> httpGet(url, token) },
) : GithubApi {

    override fun readRef(): String =
        JSONObject(fetch("$API/$repo/git/ref/heads/main")).getJSONObject("object").getString("sha")

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
        return written(send("PUT", path, body))
    }

    override fun deleteFile(path: String, message: String, sha: String): Written =
        written(send("DELETE", path, JSONObject().put("message", message).put("sha", sha)))

    private fun written(response: String): Written {
        val json = JSONObject(response)
        return Written(
            sha = json.optJSONObject("content")?.optString("sha")?.takeIf { it.isNotEmpty() },
            commitSha = json.getJSONObject("commit").getString("sha"),
        )
    }

    /** Мутации идут мимо шва [fetch]: он читающий, а тело ответа записи нужно целиком. */
    @Throws(IOException::class)
    private fun send(method: String, path: String, body: JSONObject): String {
        val conn = open("$API/$repo/contents/${encodePath(path)}", token)
        try {
            conn.requestMethod = method
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            check(conn)
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    /** Ответы GitHub приходят с переносами строк; кодировка задана явно — в репо кириллица. */
    private fun decode(base64: String): String =
        String(Base64.getMimeDecoder().decode(base64), Charsets.UTF_8)

    private companion object {
        const val API = "https://api.github.com/repos"
        const val TIMEOUT_MS = 60_000
        const val ERR_PREVIEW = 300
        val SUCCESS_RANGE = 200..299

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

        /** Пробел в пути — `%20`, не `+`; кириллица — UTF-8-проценты. */
        fun encodePath(path: String): String =
            path.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
    }
}
