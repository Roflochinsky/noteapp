package com.roflochinsky.noteapp.pipeline

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Один бинарный POST — параметры зафиксированы смоук-тестом (docs/research/stt-choice.md): nova-3,
 * language=multi, diarize+utterances+punctuate+smart_format.
 */
object DeepgramClient {
    private const val ENDPOINT =
        "https://api.deepgram.com/v1/listen" +
            "?model=nova-3&language=multi&diarize=true&utterances=true" +
            "&punctuate=true&smart_format=true"
    private const val TIMEOUT_MS = 120_000
    private const val ERR_PREVIEW = 500

    @Throws(IOException::class)
    fun transcribe(audio: File, apiKey: String): String {
        val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("Authorization", "Token $apiKey")
            conn.setRequestProperty("Content-Type", "audio/mp4")
            conn.setFixedLengthStreamingMode(audio.length())
            audio.inputStream().use { it.copyTo(conn.outputStream) }
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                val err = conn.errorStream?.bufferedReader()?.readText()?.take(ERR_PREVIEW) ?: ""
                throw IOException("Deepgram HTTP $code: $err")
            }
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }
}
