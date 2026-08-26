package com.roflochinsky.noteapp.pipeline

import java.time.LocalDate

/**
 * Задача — один `.md`-файл в `tasks/` репо заметок. Схема frontmatter живёт в
 * docs/adr/2026-08-26-tasks-as-files.md и дублировать её здесь нельзя — этот объект только читает и
 * пишет то, что там записано.
 *
 * Разбор принимает неканоничный ввод (пробелы, кавычки, `- [X]`, переставленные ключи, блочные
 * теги); сборка канонична и идемпотентна (решение LLD-9). Собирать файл целиком можно только для
 * тех, что приложение само и создало, — чужие правятся построчно.
 */
object TaskFile {
    const val STATUS_OPEN = "open"
    const val STATUS_IN_PROGRESS = "in_progress"
    const val STATUS_DONE = "done"
    const val PRIORITY_DEFAULT = "P2"
    const val DIR = "tasks/"

    private val KNOWN =
        setOf("title", "project", "priority", "status", "source", "created", "due", "done", "tags")
    private val CHECKBOX = Regex("""^\s*[-*]\s*\[([ xX])]\s*(.*)$""")

    data class Subtask(val text: String, val done: Boolean)

    data class Task(
        val path: String,
        val title: String,
        val priority: String = PRIORITY_DEFAULT,
        val status: String = STATUS_OPEN,
        val project: String? = null,
        val source: String? = null,
        val created: LocalDate? = null,
        val due: LocalDate? = null,
        /** Дата закрытия: ставится вместе со `status: done`, иначе поля нет. */
        val done: LocalDate? = null,
        val tags: List<String> = emptyList(),
        val body: String = "",
        val subtasks: List<Subtask> = emptyList(),
        /** Неизвестные ключи frontmatter — сохраняются как есть. */
        val extra: Map<String, String> = emptyMap(),
    ) {
        val isDone: Boolean
            get() = status == STATUS_DONE
    }

    fun parse(path: String, md: String): Task {
        val doc = Frontmatter.parse(md) ?: Frontmatter.Doc(emptyMap(), md.trim())
        val f = doc.fields
        return Task(
            path = path,
            title =
                f["title"]?.takeIf { it.isNotBlank() }
                    ?: path.substringAfterLast('/').removeSuffix(".md"),
            priority = f["priority"]?.takeIf { it.isNotBlank() } ?: PRIORITY_DEFAULT,
            status = f["status"]?.takeIf { it.isNotBlank() } ?: STATUS_OPEN,
            project = f["project"]?.takeIf { it.isNotBlank() },
            source = f["source"]?.takeIf { it.isNotBlank() },
            created = date(f["created"]),
            due = date(f["due"]),
            done = date(f["done"]),
            tags = Frontmatter.list(f["tags"]),
            body = doc.body,
            subtasks = subtasks(doc.body),
            extra = f.filterKeys { it !in KNOWN },
        )
    }

    fun build(task: Task): String {
        val f = LinkedHashMap<String, String>()
        f["title"] = task.title
        task.project?.let { f["project"] = it }
        f["priority"] = task.priority
        f["status"] = task.status
        task.source?.let { f["source"] = it }
        task.created?.let { f["created"] = it.toString() }
        task.due?.let { f["due"] = it.toString() }
        task.done?.let { f["done"] = it.toString() }
        if (task.tags.isNotEmpty()) f["tags"] = Frontmatter.inline(task.tags)
        f.putAll(task.extra)
        val body = task.body.trim()
        return Frontmatter.render(f) + if (body.isEmpty()) "" else "\n$body\n"
    }

    /** Имя файла задачи: дата создания + транслит-слаг, при коллизии — суффикс (решение LLD-15). */
    fun fileName(date: LocalDate, title: String, taken: Set<String>): String {
        val slug = slug(title).ifEmpty { "zadacha" }
        val names = taken.map { it.substringAfterLast('/') }.toSet()
        var name = "$date-$slug.md"
        var n = 1
        while (name in names) {
            n++
            name = "$date-$slug-$n.md"
        }
        return name
    }

    private fun date(value: String?): LocalDate? =
        value
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { LocalDate.parse(it.trim()) }.getOrNull() }

    private fun subtasks(body: String): List<Subtask> =
        body
            .lineSequence()
            .mapNotNull { CHECKBOX.find(it) }
            .map { Subtask(it.groupValues[2].trim(), it.groupValues[1] != " ") }
            .filter { it.text.isNotEmpty() }
            .toList()

    private const val RU = "абвгдеёжзийклмнопрстуфхцчшщъыьэюя"
    private val LAT =
        "a|b|v|g|d|e|e|zh|z|i|y|k|l|m|n|o|p|r|s|t|u|f|kh|ts|ch|sh|shch||y||e|yu|ya".split("|")
    private const val SLUG_MAX = 48

    private fun slug(title: String): String {
        val latin = StringBuilder()
        for (ch in title.lowercase()) {
            val i = RU.indexOf(ch)
            latin.append(if (i >= 0) LAT[i] else ch)
        }
        return latin
            .map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }
            .joinToString("")
            .split('-')
            .filter { it.isNotEmpty() }
            .joinToString("-")
            .take(SLUG_MAX)
            .trim('-')
    }
}
