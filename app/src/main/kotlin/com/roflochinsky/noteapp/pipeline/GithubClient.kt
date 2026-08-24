package com.roflochinsky.noteapp.pipeline

import android.util.Base64
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** GitHub Contents API: один PUT — один новый файл (1 коммит = 1 заметка). */
object GithubClient {
    private const val TIMEOUT_MS = 60_000
    private const val ERR_PREVIEW = 300

    /** @param repo вида "owner/name"; кидает IOException при не-2xx. */
    @Throws(IOException::class)
    fun putFile(repo: String, path: String, content: String, message: String, token: String) {
        val conn =
            URL("https://api.github.com/repos/$repo/contents/$path").openConnection()
                as HttpURLConnection
        try {
            conn.requestMethod = "PUT"
            conn.doOutput = true
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("Content-Type", "application/json")
            val body =
                JSONObject()
                    .put("message", message)
                    .put("content", Base64.encodeToString(content.toByteArray(), Base64.NO_WRAP))
                    .toString()
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            if (code !in SUCCESS_RANGE) {
                val err = conn.errorStream?.bufferedReader()?.readText()?.take(ERR_PREVIEW) ?: ""
                throw IOException("GitHub HTTP $code: $err")
            }
        } finally {
            conn.disconnect()
        }
    }

    private const val HTTP_OK_MIN = 200
    private const val HTTP_OK_MAX = 299
    private val SUCCESS_RANGE = HTTP_OK_MIN..HTTP_OK_MAX
}
