package com.roflochinsky.noteapp.pipeline

/**
 * Трёхстороннее слияние задачи на 409 (решения LLD-2 и LLD-3). Вход — разобранные структуры: `base`
 * — то, на чём владелец правил, `mine` — его результат, `theirs` — то, что сейчас в git.
 *
 * Разные поля — сливаем молча; одно и то же поле с двух сторон — побеждает git, владельцу
 * показывается расхождение (ADR app-writes-to-repo: с той стороны обычно Action с саммари).
 *
 * Правило отдаёт только вердикт. Слитый файл собирает не оно, а переигрывание `Edit` поверх свежего
 * текста в [RepoStore]: так тело файла не пересобирается и неизвестные ключи остаются на месте
 * (решение LLD-9). Собранная здесь задача была бы вторым, расходящимся способом получить тот же
 * файл — его убрали 2026-08-29 в фикс-цикле Н2.
 */
object ConflictRule {

    /** Псевдоключ неделимого текста-описания: правка с двух сторон — сразу расхождение. */
    const val DESCRIPTION = "body"

    /** Предел переигрываний 409 подряд; дальше — расхождение, а не бесконечный цикл. */
    const val MAX_REPLAYS = 3

    sealed interface Result

    /** Правки не пересеклись — операцию можно переиграть на свежем тексте. */
    data object Merged : Result

    data class Divergence(val fields: List<String>) : Result

    fun resolve(base: TaskFile.Task, mine: TaskFile.Task, theirs: TaskFile.Task): Result {
        val b = view(base)
        val m = view(mine)
        val t = view(theirs)
        val diverged =
            (b.keys + m.keys + t.keys).filter { key ->
                val mv = m[key]
                val tv = t[key]
                // Разошлись только если поле тронули с обеих сторон и по-разному.
                mv != tv && mv != b[key] && tv != b[key]
            }
        return if (diverged.isEmpty()) Merged else Divergence(diverged)
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

    /**
     * Задача плоской картой «ключ → значение»: frontmatter в наборе и порядке [TaskFile.KEYS] плюс
     * описание. Подзадачи в сравнение не входят — их сводит переигрывание правки по тексту.
     */
    private fun view(t: TaskFile.Task): Map<String, String?> = buildMap {
        putAll(TaskFile.fields(t))
        put(DESCRIPTION, description(t.body).ifEmpty { null })
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

    private val CHECKBOX = Regex("""^\s*[-*]\s*\[[ xX]]\s*.*$""")
}
