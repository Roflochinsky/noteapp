package com.roflochinsky.noteapp.pipeline

import java.time.LocalDate

/**
 * Трёхстороннее слияние задачи на 409 (решения LLD-2 и LLD-3). Вход — разобранные структуры: `base`
 * — то, на чём владелец правил, `mine` — его результат, `theirs` — то, что сейчас в git.
 *
 * Разные поля — сливаем молча; одно и то же поле с двух сторон — побеждает git, владельцу
 * показывается расхождение (ADR app-writes-to-repo: с той стороны обычно Action с саммари).
 */
object ConflictRule {

    /** Псевдоключ неделимого текста-описания: правка с двух сторон — сразу расхождение. */
    const val DESCRIPTION = "body"

    /** Предел переигрываний 409 подряд; дальше — расхождение, а не бесконечный цикл. */
    const val MAX_REPLAYS = 3

    sealed interface Result

    data class Merged(val task: TaskFile.Task) : Result

    data class Divergence(val fields: List<String>) : Result

    fun resolve(base: TaskFile.Task, mine: TaskFile.Task, theirs: TaskFile.Task): Result {
        val b = view(base)
        val m = view(mine)
        val t = view(theirs)
        val merged = LinkedHashMap<String, String?>()
        val diverged = mutableListOf<String>()
        for (key in b.keys + m.keys + t.keys) {
            val mv = m[key]
            val tv = t[key]
            when {
                mv == tv -> merged[key] = tv
                mv == b[key] -> merged[key] = tv
                tv == b[key] -> merged[key] = mv
                else -> diverged += key
            }
        }
        if (diverged.isNotEmpty()) return Divergence(diverged)
        return Merged(task(theirs, merged, subtasks(base, mine, theirs)))
    }

    /** Человеческое имя поля для строки расхождения. */
    fun label(field: String): String =
        when (field) {
            "title" -> "Заголовок"
            "project" -> "Проект"
            "priority" -> "Приоритет"
            "status" -> "Статус"
            "source" -> "Источник"
            "created" -> "Создана"
            "due" -> "Срок"
            "done" -> "Дата закрытия"
            "tags" -> "Теги"
            DESCRIPTION -> "Описание"
            else -> field
        }

    /** Задача как плоская карта «ключ → значение»: frontmatter по ключам плюс описание. */
    private fun view(t: TaskFile.Task): Map<String, String?> = buildMap {
        put("title", t.title)
        put("project", t.project)
        put("priority", t.priority)
        put("status", t.status)
        put("source", t.source)
        put("created", t.created?.toString())
        put("due", t.due?.toString())
        put("done", t.done?.toString())
        put("tags", t.tags.joinToString(", ").ifEmpty { null })
        put(DESCRIPTION, description(t.body).ifEmpty { null })
        putAll(t.extra)
    }

    /**
     * ponytail: описание — это тело файла без строк-чекбоксов и заголовка секции; делить его на
     * абзацы незачем — правка текста с двух сторон и есть расхождение.
     */
    private fun description(body: String): String =
        body
            .lineSequence()
            .filterNot { CHECKBOX.matches(it) }
            .filterNot { it.trim().equals(Edit.SUBTASKS_HEADING, ignoreCase = true) }
            .joinToString("\n")
            .trim()

    /**
     * Подзадачи сопоставляются по нормализованному тексту: чужой порядок и удаление — правда git.
     */
    private fun subtasks(
        base: TaskFile.Task,
        mine: TaskFile.Task,
        theirs: TaskFile.Task,
    ): List<TaskFile.Subtask> {
        val key = { s: TaskFile.Subtask -> Edit.normalize(s.text) }
        val b = base.subtasks.associateBy(key)
        val m = mine.subtasks.associateBy(key)
        val kept =
            theirs.subtasks.map { their ->
                val k = key(their)
                val mineDone = m[k]?.done
                val baseDone = b[k]?.done
                if (mineDone != null && mineDone != baseDone) their.copy(done = mineDone) else their
            }
        val added = mine.subtasks.filter { key(it) !in b && key(it) !in theirs.subtasks.map(key) }
        return kept + added
    }

    private fun task(
        theirs: TaskFile.Task,
        f: Map<String, String?>,
        subtasks: List<TaskFile.Subtask>,
    ): TaskFile.Task {
        val known =
            setOf(
                "title",
                "project",
                "priority",
                "status",
                "source",
                "created",
                "due",
                "done",
                "tags",
                DESCRIPTION,
            )
        return theirs.copy(
            title = f["title"].orEmpty(),
            project = f["project"],
            priority = f["priority"] ?: TaskFile.PRIORITY_DEFAULT,
            status = f["status"] ?: TaskFile.STATUS_OPEN,
            source = f["source"],
            created = date(f["created"]),
            due = date(f["due"]),
            done = date(f["done"]),
            tags = f["tags"].orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() },
            body = body(f[DESCRIPTION].orEmpty(), subtasks),
            subtasks = subtasks,
            extra = f.filterKeys { it !in known }.mapValues { it.value.orEmpty() },
        )
    }

    private fun body(description: String, subtasks: List<TaskFile.Subtask>): String =
        buildList {
                if (description.isNotEmpty()) add(description)
                if (subtasks.isNotEmpty()) {
                    add(Edit.SUBTASKS_HEADING)
                    subtasks.forEach { add("- [${if (it.done) "x" else " "}] ${it.text}") }
                }
            }
            .joinToString("\n")

    private fun date(value: String?): LocalDate? =
        value?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    private val CHECKBOX = Regex("""^\s*[-*]\s*\[[ xX]]\s*.*$""")
}
