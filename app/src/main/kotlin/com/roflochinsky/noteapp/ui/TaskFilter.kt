package com.roflochinsky.noteapp.ui

import com.roflochinsky.noteapp.pipeline.TaskFile
import java.time.LocalDate

/**
 * Порядок и рубрики списка задач (решение LLD-16). Сегодняшняя дата приходит параметром — от
 * системных часов чистая логика не зависит.
 *
 * Чекбокс в списке бинарен: `done ↔ open`, «в работе» ставится только в деталке (срез Н2), поэтому
 * «открытые» здесь — всё, что не `done`.
 */
object TaskFilter {
    /** «Сделано за месяц» из компа: закрытые позже этого окна не показываются. */
    const val DONE_WINDOW_DAYS = 30L

    val PRIORITIES = listOf("P1", "P2", "P3")

    fun open(tasks: List<TaskFile.Task>, today: LocalDate): List<TaskFile.Task> =
        tasks.filterNot { it.isDone }.sortedWith(order(today))

    /** Свежезакрытые сверху; закрытые без даты (миграция, правка руками) — в конец. */
    fun done(tasks: List<TaskFile.Task>, today: LocalDate): List<TaskFile.Task> {
        val (dated, undated) = tasks.filter { it.isDone }.partition { it.done != null }
        return dated
            .filter { !it.done!!.isBefore(today.minusDays(DONE_WINDOW_DAYS)) }
            .sortedByDescending { it.done } + undated
    }

    /** В счётчик рубрики идут только закрытые с датой внутри окна. */
    fun doneCount(tasks: List<TaskFile.Task>, today: LocalDate): Int =
        done(tasks, today).count { it.done != null }

    fun isOverdue(task: TaskFile.Task, today: LocalDate): Boolean =
        !task.isDone && task.due != null && task.due.isBefore(today)

    /** Рубрики P1→P3 в порядке компа; пустые рубрики не рисуются. */
    fun byPriority(
        tasks: List<TaskFile.Task>,
        today: LocalDate,
    ): List<Pair<String, List<TaskFile.Task>>> =
        open(tasks, today).groupBy { it.priority }.toList().sortedBy { rank(it.first) }

    private fun rank(priority: String): Int =
        PRIORITIES.indexOf(priority).takeIf { it >= 0 } ?: PRIORITIES.size

    /** Приоритет → просроченные и ближайший срок → без срока по дате создания. */
    private fun order(today: LocalDate): Comparator<TaskFile.Task> =
        compareBy(
            { rank(it.priority) },
            { if (it.due == null) 1 else 0 },
            { it.due ?: today },
            { it.created ?: LocalDate.MIN },
            { it.title },
        )
}

internal fun priorityWord(priority: String): String =
    when (priority) {
        "P1" -> "высокий"
        "P3" -> "низкий"
        else -> "обычный"
    }

private val DAYS = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")
private val MONTHS =
    listOf("янв", "фев", "мар", "апр", "мая", "июн", "июл", "авг", "сен", "окт", "ноя", "дек")
private const val WEEK = 7L

private fun dayMonth(date: LocalDate): String = "${date.dayOfMonth} ${MONTHS[date.monthValue - 1]}"

/**
 * Срок словом: близкий — днём недели, дальний — числом. Названия свои, чтобы не зависеть от ICU.
 */
internal fun dueLabel(due: LocalDate, today: LocalDate): String =
    when {
        due == today -> "сегодня"
        due == today.plusDays(1) -> "завтра"
        due.isBefore(today.plusDays(WEEK)) -> "до ${DAYS[due.dayOfWeek.value - 1]}"
        else -> "до ${dayMonth(due)}"
    }

internal fun overdueLabel(due: LocalDate, today: LocalDate): String =
    if (due == today.minusDays(1)) "просрочено · вчера" else "просрочено · ${dayMonth(due)}"
