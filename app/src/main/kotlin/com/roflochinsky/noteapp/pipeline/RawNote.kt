package com.roflochinsky.noteapp.pipeline

import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Сборка raw-заметки — контракт с Action: docs/specs/2026-08-24-note-format.md. */
object RawNote {

    private val ID_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    data class Input(
        val noteId: String,
        val zone: ZoneOffset,
        val durationSec: Long,
        val device: String,
        val marksMs: List<Long> = emptyList(),
        val transcriptMd: String,
    )

    fun build(input: Input): String {
        val recorded = LocalDateTime.parse(input.noteId, ID_FORMAT).atOffset(input.zone)
        val moments =
            if (input.marksMs.isEmpty()) ""
            else
                "## Моменты\n\n" +
                    input.marksMs.joinToString("\n") { "- ${TranscriptMapper.timecode(it)}" } +
                    "\n\n"
        return buildString {
            append("---\n")
            append("recorded: ${recorded.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)}\n")
            append("duration: ${duration(input.durationSec)}\n")
            append("device: ${input.device}\n")
            append("status: raw\n")
            append("---\n\n")
            append(moments)
            append("## Транскрипт\n\n")
            append(input.transcriptMd)
            append("\n")
        }
    }

    fun fileName(noteId: String): String {
        val t = LocalDateTime.parse(noteId, ID_FORMAT)
        return t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")) + ".md"
    }

    private fun duration(sec: Long): String {
        val h = sec / SEC_IN_HOUR
        val m = sec % SEC_IN_HOUR / SEC_IN_MIN
        val s = sec % SEC_IN_MIN
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    private const val SEC_IN_HOUR = 3600
    private const val SEC_IN_MIN = 60
}
