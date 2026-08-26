package com.roflochinsky.noteapp.pipeline

/**
 * Заметка — файл `.md` репо заметок (docs/specs/2026-08-24-note-format.md, поля `project`/`tags` из
 * ADR v2). Читает и raw от телефона, и done от Action, и заметки v1 с чекбоксами в «Задачах», и v2
 * со ссылками на `tasks/` — до миграции репо живёт в смешанном состоянии.
 *
 * Поля хранятся картой в порядке файла: неизвестные ключи не теряются, а тело ниже frontmatter не
 * пересобирается.
 */
object NoteFile {
    const val STATUS_DONE = "done"
    const val SUMMARY = "## Саммари"

    data class Note(val path: String, val fields: Map<String, String>, val body: String) {
        val title: String?
            get() = field("title")

        val type: String?
            get() = field("type")

        val status: String?
            get() = field("status")

        val project: String?
            get() = field("project")

        val participants: List<String>
            get() = Frontmatter.list(fields["participants"])

        val tags: List<String>
            get() = Frontmatter.list(fields["tags"])

        /** Текст секции `## Заголовок` до следующей секции; null — секции нет. */
        fun section(header: String): String? {
            val start = body.indexOf(header)
            if (start < 0) return null
            val rest = body.substring(start + header.length)
            val next = rest.indexOf("\n## ")
            return if (next < 0) rest else rest.take(next)
        }

        private fun field(key: String): String? = fields[key]?.takeIf { it.isNotBlank() }
    }

    /** null — файл не нашего формата (нет frontmatter). */
    fun parse(path: String, md: String): Note? =
        Frontmatter.parse(md)?.let { Note(path, it.fields, it.body) }

    fun build(note: Note): String {
        val body = note.body.trim()
        return Frontmatter.render(note.fields) + if (body.isEmpty()) "" else "\n$body\n"
    }
}
