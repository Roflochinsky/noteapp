package com.roflochinsky.noteapp.pipeline

import java.io.File
import java.io.IOException
import org.json.JSONException
import org.json.JSONObject

/**
 * Completed no-transcript responses stop automatic billable replay; an explicit retry is one-use.
 */
internal object EmptyTranscription {
    const val REASON =
        "В ответе распознавания нет реплик. Аудио сохранено; " +
            "повтор — только по кнопке «Повторить»."
    private const val RETRY = "retry-empty-stt.txt"

    fun stopped(dir: File): Boolean {
        if (!savedEmpty(dir) || File(dir, RETRY).isFile) return false
        NotesStore.writeAtomic(File(dir, NotesStore.STATUS), REASON)
        return true
    }

    fun request(dir: File) {
        if (savedEmpty(dir)) NotesStore.writeAtomic(File(dir, RETRY), "requested")
    }

    fun consume(dir: File) {
        val marker = File(dir, RETRY)
        if (marker.exists() && !marker.delete()) throw IOException("Не удалось начать явный повтор")
    }

    private fun savedEmpty(dir: File): Boolean {
        val response = File(dir, NotesStore.TRANSCRIPT_JSON)
        if (!response.isFile) return false
        return try {
            val raw = response.readText()
            val json = JSONObject(raw)
            val parsed =
                when {
                    json.has("words") -> TranscriptMapper.fromElevenLabsJson(raw)
                    json.has("results") -> TranscriptMapper.fromDeepgramJson(raw)
                    else -> null
                }
            parsed?.let { TranscriptMapper.toMarkdown(it).isBlank() } ?: false
        } catch (_: JSONException) {
            false
        }
    }
}
