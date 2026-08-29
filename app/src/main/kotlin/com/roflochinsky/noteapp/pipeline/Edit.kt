package com.roflochinsky.noteapp.pipeline

import java.time.LocalDate

/**
 * Смысловая единица правки (решение LLD-1): очередь хранит `Edit` + `baseBlobSha`, а не готовый
 * текст файла — только так правку можно переиграть на свежем SHA и слить трёхсторонне.
 *
 * [apply] — чистая функция: frontmatter правится построчно, тело файла не пересобирается,
 * неизвестные ключи остаются на месте (решение LLD-9).
 */
sealed interface Edit {

    /** Значение `null` убирает ключ из frontmatter («поле опускается, если его нет», ADR). */
    data class SetField(val key: String, val value: String?) : Edit

    data class SetTitle(val title: String) : Edit

    /** Статус и дата закрытия ходят парой: `done` ставится вместе со `status: done`. */
    data class SetStatus(val status: String, val done: LocalDate?) : Edit

    data class ToggleSubtask(val text: String, val done: Boolean) : Edit

    data class AddSubtask(val text: String) : Edit

    /**
     * ponytail: у создания нет базы, переигрывать нечего — операция несёт готовый текст нового
     * файла; поля собирает [TaskFile.build] в момент нажатия.
     */
    data class CreateTask(val content: String) : Edit

    data object DeleteFile : Edit

    /** Ключ склейки: две правки одного поля одного файла — одна операция, побеждает последняя. */
    val target: String
        get() =
            when (this) {
                is SetField -> "field:$key"
                is SetTitle -> "field:${TITLE}"
                is SetStatus -> "field:${STATUS}"
                is ToggleSubtask -> "subtask:${normalize(text)}"
                is AddSubtask -> "subtask:${normalize(text)}"
                is CreateTask -> "create"
                DeleteFile -> "delete"
            }

    /** Ключи frontmatter, которых касается правка, — по ним считается расхождение на 409. */
    val fields: List<String>
        get() =
            when (this) {
                is SetField -> listOf(key)
                is SetTitle -> listOf(TITLE)
                is SetStatus -> listOf(STATUS)
                else -> emptyList()
            }

    companion object {
        const val TITLE = "title"
        const val STATUS = "status"
        const val DONE = "done"
        const val SUBTASKS_HEADING = "## Подзадачи"

        /** Порядок ключей из ADR — по нему новый ключ встаёт на своё место, а не в хвост. */
        private val ORDER =
            listOf(TITLE, "project", "priority", STATUS, "source", "created", "due", DONE, "tags")
        private val CHECKBOX = Regex("""^(\s*[-*]\s*\[)([ xX])(]\s*)(.*)$""")

        fun apply(text: String, edit: Edit): String =
            when (edit) {
                is CreateTask -> edit.content
                DeleteFile -> text
                is SetTitle -> field(text, TITLE, edit.title)
                is SetField -> field(text, edit.key, edit.value)
                is SetStatus ->
                    field(field(text, STATUS, edit.status), DONE, edit.done?.toString())
                is ToggleSubtask -> toggle(text, edit.text, edit.done)
                is AddSubtask -> add(text, edit.text)
            }

        fun normalize(text: String): String = text.trim().lowercase().replace(WS, " ")

        private val WS = Regex("""\s+""")

        /** Построчная правка frontmatter: свой ключ переписывается, чужие не двигаются. */
        private fun field(text: String, key: String, value: String?): String {
            val lines = text.replace("\r\n", "\n").split("\n").toMutableList()
            val end = fenceEnd(lines) ?: return text
            val at = lines.subList(1, end).indexOfFirst { keyOf(it) == key }
            if (at >= 0) {
                val from = at + 1
                var to = from + 1
                while (to < end && lines[to].trimStart().startsWith("- ")) to++
                repeat(to - from) { lines.removeAt(from) }
                if (value != null) lines.add(from, Frontmatter.field(key, value))
            } else if (value != null) {
                lines.add(insertAt(lines, end, key), Frontmatter.field(key, value))
            }
            return lines.joinToString("\n")
        }

        /** Индекс второго `---`; `null` — файл не нашего формата, тогда правку не применяем. */
        private fun fenceEnd(lines: List<String>): Int? {
            if (lines.firstOrNull()?.trim() != Frontmatter.FENCE) return null
            val end = lines.drop(1).indexOfFirst { it.trim() == Frontmatter.FENCE }
            return if (end < 0) null else end + 1
        }

        private fun keyOf(line: String): String? =
            line.substringBefore(':').trim().takeIf { it.isNotEmpty() && ':' in line }

        private fun rank(key: String?): Int =
            ORDER.indexOf(key).takeIf { it >= 0 } ?: ORDER.size

        private fun insertAt(lines: List<String>, end: Int, key: String): Int {
            val mine = rank(key)
            for (i in 1 until end) {
                val k = keyOf(lines[i]) ?: continue
                if (rank(k) > mine) return i
            }
            return end
        }

        /** Подзадача ищется по нормализованному тексту, не по позиции (решение LLD-2). */
        private fun toggle(text: String, subtask: String, done: Boolean): String {
            val want = normalize(subtask)
            return text.replace("\r\n", "\n").split("\n").joinToString("\n") { line ->
                val m = CHECKBOX.find(line)
                if (m != null && normalize(m.groupValues[4]) == want) {
                    m.groupValues[1] + (if (done) "x" else " ") + m.groupValues[3] +
                        m.groupValues[4]
                } else {
                    line
                }
            }
        }

        private fun add(text: String, subtask: String): String {
            val lines = text.replace("\r\n", "\n").trimEnd().split("\n").toMutableList()
            val heading = lines.indexOfFirst { it.trim().equals(SUBTASKS_HEADING, true) }
            val row = "- [ ] ${subtask.trim()}"
            if (heading < 0) {
                lines += listOf("", SUBTASKS_HEADING, row)
            } else {
                var at = heading + 1
                while (at < lines.size && (CHECKBOX.matches(lines[at]) || lines[at].isBlank())) at++
                lines.add(at, row)
            }
            return lines.joinToString("\n") + "\n"
        }
    }
}
