package com.roflochinsky.noteapp.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Маппер проверяется на ЖИВОМ ответе Deepgram (docs/research/deepgram-sample-response.json). */
class TranscriptMapperTest {

    private val sample =
        requireNotNull(javaClass.classLoader?.getResource("deepgram-sample-response.json")) {
                "нет образца в test resources"
            }
            .readText()

    @Test
    fun `живой ответ парсится в реплики со спикерами и тайм-кодами`() {
        val t = TranscriptMapper.fromDeepgramJson(sample)
        assertTrue("должны быть реплики", t.utterances.isNotEmpty())
        val first = t.utterances.first()
        assertEquals(0, first.speaker)
        assertTrue("startMs должен быть > 0", first.startMs > 0)
        assertTrue("текст реплики не пуст", first.text.isNotBlank())
    }

    @Test
    fun `markdown по формату note-format - Спикер N и мм-сс`() {
        val t = TranscriptMapper.fromDeepgramJson(sample)
        val md = TranscriptMapper.toMarkdown(t)
        // Спикер нумеруется с 1 (Deepgram даёт 0), тайм-код [мм:сс]
        assertTrue(
            md,
            md.lineSequence().first().matches(Regex("""\[\d{2}:\d{2}] Спикер \d+: .+""")),
        )
        assertTrue("спикер 0 стал Спикером 1", md.contains("Спикер 1:"))
    }

    @Test
    fun `тайм-код форматируется мм-сс с ведущими нулями`() {
        assertEquals("[00:05]", TranscriptMapper.timecode(5_400))
        assertEquals("[01:31]", TranscriptMapper.timecode(91_000))
        assertEquals("[12:31]", TranscriptMapper.timecode(751_000))
    }
}
