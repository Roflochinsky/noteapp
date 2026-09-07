package com.roflochinsky.noteapp.pipeline

import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Запрос к ElevenLabs собирается офлайн — через шов транспорта, как у [GithubClient] (решение Р4
 * плана: у `DeepgramClient` тестов нет ровно потому, что `URL(...)` зашит внутрь `object`).
 *
 * Проверяется то, что стоит денег и молчит при поломке: замороженный контракт запроса (ADR
 * `2026-09-06-stt-elevenlabs-scribe-v2`, Решение 2) и разбор отказа. Живой запрос остаётся за
 * прокликкой владельца — ключа у гейта нет и быть не должно.
 */
class ElevenLabsClientTest {

    @get:Rule val tmp = TemporaryFolder()

    private var url: String? = null
    private var headers: Map<String, String> = emptyMap()
    private var declaredLength = 0L
    private val sent = Recorder()

    private val audioBytes = "postanovochnye-bajty-zvuka"

    private fun audio(bytes: String = audioBytes): File =
        tmp.newFile("zapis.m4a").apply { writeText(bytes) }

    /**
     * Тело запроса и размер самой крупной записи в поток. Второе — единственное, чем «аудио идёт
     * потоком» отличается от «аудио собрано в `ByteArray`»: байты тела и объявленная длина у них
     * совпадают.
     */
    private class Recorder : ByteArrayOutputStream() {
        var maxWrite = 0
            private set

        override fun write(b: ByteArray, off: Int, len: Int) {
            maxWrite = maxOf(maxWrite, len)
            super.write(b, off, len)
        }
    }

    private fun client(reply: SttReply) =
        ElevenLabsClient { requestUrl, requestHeaders, length, body ->
            url = requestUrl
            headers = requestHeaders
            declaredLength = length
            body(sent)
            reply
        }

    private fun body(): String = sent.toString(Charsets.UTF_8.name())

    private fun boundary(): String =
        checkNotNull(headers["Content-Type"]) { "нет Content-Type: $headers" }
            .substringAfter("boundary=")

    /** Поля multipart без заголовков частей: имя → значение. */
    private fun fields(): Map<String, String> =
        body().split("--${boundary()}").drop(1).dropLast(1).associate { part ->
            val head = part.substringAfter("\r\n").substringBefore("\r\n\r\n")
            val name = checkNotNull(Regex("""name="([^"]+)"""").find(head)) { head }.groupValues[1]
            name to part.substringAfter("\r\n\r\n").removeSuffix("\r\n")
        }

    /**
     * Полей ровно шесть: `keyterms` в этом срезе нет вообще — словарь приезжает частью 2 вместе с
     * первым потребителем. Сравнение картой целиком, а не по одному полю: лишнее поле стоит денег
     * (`keyterms` — плюс 20 % к цене прогона) и здесь обязано валить тест.
     */
    @Test
    fun `запрос — шесть полей multipart, ключ заголовком, файл частью file`() {
        client(SttReply(200, """{"words":[]}""")).transcribe(audio(), "xi-test-kluch")

        assertEquals("https://api.elevenlabs.io/v1/speech-to-text", url)
        assertEquals("xi-test-kluch", headers["xi-api-key"])
        assertTrue(headers.toString(), boundary().isNotEmpty())
        assertEquals(
            mapOf(
                "model_id" to "scribe_v2",
                "language_code" to "rus",
                "diarize" to "true",
                "timestamps_granularity" to "word",
                "tag_audio_events" to "true",
                "file" to audioBytes,
            ),
            fields(),
        )
        assertTrue(
            body(),
            body()
                .contains(
                    "name=\"file\"; filename=\"audio.m4a\"\r\nContent-Type: audio/mp4\r\n\r\n"
                ),
        )
    }

    /**
     * Длина объявляется заранее (`setFixedLengthStreamingMode`), потому что часовая запись — около
     * 48 МБ: собрать тело в `ByteArray` значит уронить приложение по памяти. Расхождение
     * объявленной длины с фактически записанной обрывает запрос в бою и здесь обязано быть видно.
     */
    @Test
    fun `объявленная длина тела совпадает с записанной`() {
        client(SttReply(200, "{}")).transcribe(audio(), "xi-test-kluch")
        assertEquals(sent.size().toLong(), declaredLength)
    }

    /**
     * Аудио уходит в поток **кусками**, а не одним массивом с записью целиком: часовая запись —
     * около 48 МБ, и сборка её в `ByteArray` роняет приложение владельца по памяти. Соседний тест
     * подмены не увидит — ни объявленная длина, ни байты тела от неё не меняются. Мерило общее, а
     * не про размер буфера: у чтения файла целиком самая крупная запись равна файлу, у потоковой
     * отправки — меньше него.
     */
    @Test
    fun `аудио уходит в поток кусками, а не файлом целиком`() {
        val big = "z".repeat(20_000)
        client(SttReply(200, "{}")).transcribe(audio(big), "xi-test-kluch")

        assertTrue("тело ${sent.size()} Б короче записи ${big.length} Б", sent.size() > big.length)
        assertTrue(
            "самая крупная запись ${sent.maxWrite} Б при записи ${big.length} Б",
            sent.maxWrite < big.length,
        )
    }

    /**
     * Отдельный тип отказа, а не голый [java.io.IOException]: без кода воркер не отличит «ключ сам
     * не починится» (401) от «сеть моргнула» и будет ретраить по 48 МБ трафика.
     */
    @Test
    fun `не-2xx приходит как SttError с кодом и телом`() {
        val client = client(SttReply(401, """{"detail":"invalid_api_key"}"""))
        val e = assertThrows(SttError::class.java) { client.transcribe(audio(), "protuhshij") }
        assertEquals(401, e.code)
        assertTrue(e.body, e.body.contains("invalid_api_key"))
    }

    @Test
    fun `успешный ответ отдаётся телом как есть`() {
        val json = """{"words":[{"text":"раз","start":0.0,"end":0.2,"type":"word"}]}"""
        assertEquals(json, client(SttReply(200, json)).transcribe(audio(), "xi-test-kluch"))
    }
}
