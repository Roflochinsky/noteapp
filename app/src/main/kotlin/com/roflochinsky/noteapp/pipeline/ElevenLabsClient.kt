package com.roflochinsky.noteapp.pipeline

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Распознавание речи: один multipart-POST в ElevenLabs Scribe v2 (ADR
 * `2026-09-06-stt-elevenlabs-scribe-v2`). Параметры заморожены Решением 2 ADR и совпадают с
 * прогоном стенда `bin/stt-bench`.
 *
 * @param post шов транспорта (адрес, заголовки, длина тела, запись тела) — в бою
 *   `HttpURLConnection`, в тестах фейк из [ElevenLabsClientTest]. Так же устроен [GithubClient]:
 *   без шва проверить состав тела и разбор отказа можно было бы только живым запросом с ключом
 *   владельца, а у `DeepgramClient` с зашитым внутрь `URL(...)` тестов поэтому нет ни одного.
 */
class ElevenLabsClient(
    private val post: (String, Map<String, String>, Long, (OutputStream) -> Unit) -> SttReply =
        { url, headers, length, body ->
            httpPost(url, headers, length, body)
        }
) {

    /**
     * Тело **стримится**: пролог и эпилог в память, аудио — потоком из файла. Часовая запись это
     * около 48 МБ, собрать её в `ByteArray` значит уронить приложение по памяти, поэтому длина
     * объявляется заранее ([HttpURLConnection.setFixedLengthStreamingMode] через шов).
     */
    @Throws(IOException::class)
    fun transcribe(audio: File, apiKey: String): String {
        val boundary = "----noteapp-" + UUID.randomUUID().toString().replace("-", "")
        val prologue =
            buildString {
                    FIELDS.forEach { (name, value) ->
                        append("--$boundary\r\n")
                        append("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                        append("$value\r\n")
                    }
                    append("--$boundary\r\n")
                    append("Content-Disposition: form-data; name=\"file\"; ")
                    append("filename=\"$FILENAME\"\r\n")
                    append("Content-Type: $AUDIO_TYPE\r\n\r\n")
                }
                .toByteArray()
        val epilogue = "\r\n--$boundary--\r\n".toByteArray()
        val headers =
            mapOf(
                "xi-api-key" to apiKey,
                "Content-Type" to "multipart/form-data; boundary=$boundary",
            )
        val reply =
            post(ENDPOINT, headers, prologue.size + audio.length() + epilogue.size) { out ->
                out.write(prologue)
                audio.inputStream().use { it.copyTo(out) }
                out.write(epilogue)
            }
        if (reply.code !in SUCCESS_RANGE) throw SttError(reply.code, reply.body.take(ERR_PREVIEW))
        return reply.body
    }

    private companion object {
        const val ENDPOINT = "https://api.elevenlabs.io/v1/speech-to-text"

        /**
         * Замороженный контракт запроса (Решение 2 ADR) — ровно шесть полей вместе с `file`.
         * `keyterms` здесь нет: словарь приезжает частью 2 вместе с первым потребителем.
         */
        val FIELDS =
            linkedMapOf(
                "model_id" to "scribe_v2",
                "language_code" to "rus",
                "diarize" to "true",
                "timestamps_granularity" to "word",
                "tag_audio_events" to "true",
            )

        /** Так слал стенд: имя части фиксировано, тип — контейнер записи телефона. */
        const val FILENAME = "audio.m4a"
        const val AUDIO_TYPE = "audio/mp4"

        const val CONNECT_TIMEOUT_MS = 30_000

        /**
         * Пять минут на чтение ответа. Продуктовый бюджет спеки «≤ 3 мин от стопа до транскрипта» —
         * другая величина: это про то, сколько владелец ждёт, а не про то, на какой секунде рвать
         * соединение. Стенд считал часовую запись 70–97 с, но ответ на 2:59 обрывать нельзя.
         */
        const val READ_TIMEOUT_MS = 300_000

        const val ERR_PREVIEW = 200
        val SUCCESS_RANGE = 200..299

        @Throws(IOException::class)
        fun httpPost(
            url: String,
            headers: Map<String, String>,
            length: Long,
            body: (OutputStream) -> Unit,
        ): SttReply {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                headers.forEach { (name, value) -> conn.setRequestProperty(name, value) }
                conn.setFixedLengthStreamingMode(length)
                conn.outputStream.use(body)
                val code = conn.responseCode
                val stream = if (code in SUCCESS_RANGE) conn.inputStream else conn.errorStream
                return SttReply(code, stream?.bufferedReader()?.readText().orEmpty())
            } finally {
                conn.disconnect()
            }
        }
    }
}

/** Ответ транспорта до разбора: код и тело. Код нужен целиком — по нему воркер решает судьбу. */
data class SttReply(val code: Int, val body: String)

/**
 * Не-2xx от ElevenLabs. Отдельный тип, а не голый [IOException]: 401 значит «ключ сам не починится»
 * и повторять запрос бессмысленно, а каждая попытка — 48 МБ трафика.
 */
class SttError(val code: Int, val body: String) : IOException("ElevenLabs HTTP $code: $body")
