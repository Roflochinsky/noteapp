package com.roflochinsky.noteapp.pipeline

import kotlin.math.roundToLong
import org.json.JSONObject

data class Utterance(val speaker: Int, val startMs: Long, val text: String)

data class Transcript(val utterances: List<Utterance>)

/**
 * Ответ распознавания → реплики формата заметки (docs/specs/2026-08-24-note-format.md): `[мм:сс]
 * Спикер N: текст`, спикеры нумеруются с 1.
 *
 * Разборов два, и оба нужны: [fromElevenLabsJson] — боевой путь после ADR
 * `2026-09-06-stt-elevenlabs-scribe-v2`, [fromDeepgramJson] — путь отката того же ADR и старые
 * `transcript.json`, лежащие рядом с уже записанными заметками. Диспетчера «выбрать разбор по форме
 * JSON» здесь нет: в приложении `transcript.json` пока никто не читает, читатели приезжают
 * частью 3.
 */
object TranscriptMapper {

    fun fromDeepgramJson(json: String): Transcript {
        val utterances = JSONObject(json).getJSONObject("results").optJSONArray("utterances")
        val result = mutableListOf<Utterance>()
        if (utterances != null) {
            for (i in 0 until utterances.length()) {
                val u = utterances.getJSONObject(i)
                result +=
                    Utterance(
                        speaker = u.optInt("speaker", 0),
                        startMs = (u.getDouble("start") * MS_IN_SECOND).toLong(),
                        text = u.getString("transcript").trim(),
                    )
            }
        }
        return Transcript(result)
    }

    /**
     * ElevenLabs Scribe v2: слова со `speaker_id` → реплики (ADR
     * `2026-09-06-stt-elevenlabs-scribe-v2`, Решение 3). Реплика — подряд идущие элементы одного
     * спикера с паузой короче [GAP_MS]; пауза считается от `end` предыдущего элемента до `start`
     * следующего — так же режет стенд `bin/stt-compare-html` (`GAP = 1.5`), с которым сверяется
     * критерий приёмки 2.
     *
     * Арифметика в миллисекундах: на секундах-дробях порог решается нечестно — свидетель 1317,092 →
     * 1318,592 в `double` даёт 1499,9999… и пауза ровно в 1,5 с уезжает не в ту сторону.
     *
     * `spacing` пропускается: пробелы ставит склейка. `audio_event` идёт в текст **как пришёл** —
     * квадратные скобки уже в ответе вендора, обернуть их ещё раз значит выдать `[[смеется]]`.
     */
    fun fromElevenLabsJson(json: String): Transcript {
        val words = JSONObject(json).optJSONArray("words") ?: return Transcript(emptyList())
        val result = mutableListOf<Utterance>()
        var prevEndMs = 0L
        for (i in 0 until words.length()) {
            val w = words.getJSONObject(i)
            val type = w.getString("type")
            if (type == SPACING) continue
            val speaker = speakerOf(w.optString("speaker_id"))
            val text = w.getString("text")
            val startMs = millis(w.getDouble("start"))
            val last = result.lastOrNull()
            if (last != null && last.speaker == speaker && startMs - prevEndMs < GAP_MS) {
                result[result.lastIndex] = last.copy(text = "${last.text} $text")
            } else {
                result += Utterance(speaker, startMs, text)
            }
            prevEndMs = millis(w.getDouble("end"))
        }
        return Transcript(result)
    }

    /**
     * Номер спикера — цифра из `speaker_id` (`speaker_0` → 0), а не порядок появления: без
     * состояния, совпадает с нумерацией Deepgram и не зависит от того, с какого места вырезан кусок
     * ответа. Слово «Спикер N» делает [toMarkdown], здесь ничего не прибавляется. Идентификатор без
     * цифры — 0: форма поля вендорская, а нумерация в заметке ломаться не должна.
     */
    private fun speakerOf(id: String): Int = id.filter { it.isDigit() }.toIntOrNull() ?: 0

    private fun millis(seconds: Double): Long = (seconds * MS_IN_SECOND).roundToLong()

    fun toMarkdown(t: Transcript): String =
        t.utterances.joinToString("\n") {
            "${timecode(it.startMs)} Спикер ${it.speaker + 1}: ${it.text}"
        }

    fun timecode(ms: Long): String {
        val totalSec = ms / MS_IN_SECOND.toLong()
        val min = totalSec / SECONDS_IN_MINUTE
        val sec = totalSec % SECONDS_IN_MINUTE
        return "[%02d:%02d]".format(min, sec)
    }

    private const val MS_IN_SECOND = 1000.0
    private const val SECONDS_IN_MINUTE = 60

    /** Пауза, с которой начинается новая реплика: `bin/stt-compare-html` → `GAP = 1.5`. */
    private const val GAP_MS = 1500

    private const val SPACING = "spacing"
}
