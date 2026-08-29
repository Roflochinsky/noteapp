package com.roflochinsky.noteapp.pipeline

/**
 * YAML-frontmatter ровно в том объёме, который нужен формату заметок и задач (схема —
 * docs/adr/2026-08-26-tasks-as-files.md): плоские `ключ: значение`, кавычки, списки инлайном и
 * блоком. Разбор принимает неканоничное, запись всегда каноничная (решение LLD-9).
 *
 * ponytail: полноценный YAML-парсер не нужен — схема плоская, вложенности в формате нет.
 */
internal object Frontmatter {
    const val FENCE = "---"

    /** [fields] в порядке файла (неизвестные ключи тоже), [body] — всё ниже второго `---`. */
    data class Doc(val fields: Map<String, String>, val body: String)

    /** null — фронтматтера нет (файл не нашего формата). */
    fun parse(md: String): Doc? {
        val lines = md.replace("\r\n", "\n").lines()
        if (lines.firstOrNull()?.trim() != FENCE) return null
        val end = lines.drop(1).indexOfFirst { it.trim() == FENCE }
        return if (end < 0) {
            null
        } else {
            Doc(fields(lines.subList(1, end + 1)), lines.drop(end + 2).joinToString("\n").trim())
        }
    }

    /** Блочный список сводится к инлайну — пишем всегда одним видом. */
    private fun fields(head: List<String>): Map<String, String> {
        val acc = Acc()
        for (raw in head) {
            val line = raw.trim()
            val colon = line.indexOf(':')
            when {
                line.isEmpty() -> Unit
                acc.expectsItems && line.startsWith("- ") ->
                    acc.item(unquote(line.removePrefix("- ")))
                colon <= 0 -> acc.flush()
                line.substring(colon + 1).isBlank() -> acc.open(line.take(colon).trim())
                else -> acc.set(line.take(colon).trim(), unquote(line.substring(colon + 1)))
            }
        }
        acc.flush()
        return acc.fields
    }

    /** Копилка разбора: ключ, у которого значение приходит следующими строками-пунктами. */
    private class Acc {
        val fields = LinkedHashMap<String, String>()
        private var pending: String? = null
        private val block = mutableListOf<String>()

        val expectsItems: Boolean
            get() = pending != null

        fun item(text: String) {
            block += text
        }

        fun open(key: String) {
            flush()
            pending = key
        }

        fun set(key: String, value: String) {
            flush()
            fields[key] = value
        }

        fun flush() {
            pending?.let { fields[it] = if (block.isEmpty()) "" else inline(block) }
            pending = null
            block.clear()
        }
    }

    /** Пустые значения не пишутся вовсе — «поле опускается, если его нет» (ADR). */
    fun render(fields: Map<String, String>): String =
        fields.entries
            .filter { it.value.isNotEmpty() }
            .joinToString("\n", prefix = "$FENCE\n", postfix = "\n$FENCE\n") { field(it.key, it.value) }

    /** Одна строка frontmatter — единственное место, где решается вопрос кавычек. */
    fun field(key: String, value: String): String = "$key: ${quote(value)}"

    fun list(value: String?): List<String> =
        value
            .orEmpty()
            .trim()
            .removePrefix("[")
            .removeSuffix("]")
            .split(',')
            .map { unquote(it.trim()) }
            .filter { it.isNotEmpty() }

    fun inline(values: List<String>): String = values.joinToString(", ", "[", "]")

    private fun unquote(value: String): String {
        val v = value.trim()
        val quoted =
            v.length >= 2 &&
                ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'")))
        return if (quoted) v.substring(1, v.length - 1) else v
    }

    /**
     * Кавычки — только когда YAML их требует: `ключ: значение` внутри строки, решётка, краевые
     * пробелы. Время `18:07:32+03:00` и длительность `12:31` кавычек не требуют.
     */
    private fun needsQuotes(value: String): Boolean =
        value.contains(": ") || value.endsWith(":") || value.contains('#') || value != value.trim()

    private fun quote(value: String): String = if (needsQuotes(value)) "\"$value\"" else value
}
