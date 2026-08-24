package com.roflochinsky.noteapp.pipeline

/** Разбор done-заметки (после Action) — контракт docs/specs/2026-08-24-note-format.md. */
object DoneNoteParser {

    data class DoneNote(
        val title: String,
        val type: String,
        val participants: List<String>,
        val summaryMd: String,
    )

    /** null — если файл ещё raw или не наш формат. */
    fun parse(md: String): DoneNote? {
        val fm = frontmatter(md) ?: return null
        if (fm["status"] != "done") return null
        val summary = section(md, "## Саммари") ?: return null
        val participants =
            fm["participants"]
                .orEmpty()
                .removePrefix("[")
                .removeSuffix("]")
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        return DoneNote(
            title = fm["title"] ?: "Без названия",
            type = fm["type"] ?: "другое",
            participants = participants,
            summaryMd = summary.trim(),
        )
    }

    private fun frontmatter(md: String): Map<String, String>? {
        val lines = md.lineSequence().toList()
        val end =
            if (lines.firstOrNull()?.trim() != "---") -1
            else lines.drop(1).indexOfFirst { it.trim() == "---" }
        return if (end < 0) null
        else
            lines
                .subList(1, end + 1)
                .mapNotNull { line ->
                    val i = line.indexOf(':')
                    if (i <= 0) null else line.take(i).trim() to line.substring(i + 1).trim()
                }
                .toMap()
    }

    private fun section(md: String, header: String): String? {
        val start = md.indexOf(header)
        if (start < 0) return null
        val body = md.substring(start + header.length)
        val next = body.indexOf("\n## ")
        return if (next < 0) body else body.take(next)
    }
}
