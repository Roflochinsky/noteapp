package com.roflochinsky.noteapp.pipeline

/** Вид «done-заметка» поверх [NoteFile] — то, что деталка v1 показывает как саммари. */
object DoneNoteParser {

    data class DoneNote(
        val title: String,
        val type: String,
        val participants: List<String>,
        val summaryMd: String,
    )

    /** null — если файл ещё raw или не наш формат. */
    fun parse(md: String): DoneNote? {
        val note = NoteFile.parse("", md) ?: return null
        if (note.status != NoteFile.STATUS_DONE) return null
        val summary = note.section(NoteFile.SUMMARY) ?: return null
        return DoneNote(
            title = note.title ?: "Без названия",
            type = note.type ?: "другое",
            participants = note.participants,
            summaryMd = summary.trim(),
        )
    }
}
