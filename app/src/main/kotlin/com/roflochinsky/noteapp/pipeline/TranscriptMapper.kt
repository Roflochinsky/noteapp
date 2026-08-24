package com.roflochinsky.noteapp.pipeline

import org.json.JSONObject

data class Utterance(val speaker: Int, val startMs: Long, val text: String)

data class Transcript(val utterances: List<Utterance>)

/**
 * Deepgram utterances → реплики формата заметки (docs/specs/2026-08-24-note-format.md): `[мм:сс]
 * Спикер N: текст`, спикеры нумеруются с 1.
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
}
