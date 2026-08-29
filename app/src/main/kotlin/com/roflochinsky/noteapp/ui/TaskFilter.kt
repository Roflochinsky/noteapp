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

    val STATUSES = listOf(TaskFile.STATUS_OPEN, TaskFile.STATUS_IN_PROGRESS, TaskFile.STATUS_DONE)

    /**
     * Окна чипа «Срок». Срок — единственная ось, у которой нет списка значений: дат столько же,
     * сколько задач, и радио по ним было бы бесполезно. Поэтому шторка предлагает окна — те четыре,
     * что записаны в срезе (`bd nikitatrubaev-0rk.25`); комп v2 шторку «Срока» не рисует вовсе,
     * выдумывать сверх записанного нечего.
     *
     * Окна нарочно не разбиение: «сегодня» лежит внутри «на неделе». Это выбор владельца из одного
     * радио, а не рубрики списка, — пересечение здесь ничего не ломает.
     */
    const val DUE_TODAY = "today"

    const val DUE_WEEK = "week"

    /** То же «просрочено», что рисует строка списка: у закрытой задачи просрочки нет. */
    const val DUE_OVERDUE = "overdue"

    const val DUE_NONE = "none"

    val DUES = listOf(DUE_TODAY, DUE_WEEK, DUE_OVERDUE, DUE_NONE)

    /**
     * «Без проекта» как значение фильтра. Пустой `project` у задачи и «не фильтруем по проекту» —
     * разные вещи, а `null` в [Filter] уже занят вторым, поэтому первому нужен свой знак. Символ
     * непечатный и записан escape-последовательностью: проекта с таким именем в `projects.md` не
     * заведёшь, а сырой байт в исходнике сделал бы файл для git бинарным — дифф и `blame` по нему
     * пропали бы.
     */
    const val NO_PROJECT = "\u0000"

    /** Чип, он же ось фильтра: у каждой своя шторка со своими счётчиками. */
    enum class Facet {
        PROJECT,
        PRIORITY,
        STATUS,
        TAG,
        DUE,
    }

    /**
     * Состояние чипов и строки поиска. `null` в поле — «этот чип сброшен». Поиск — подстрочный по
     * заголовку задачи (решение владельца 2026-08-26 (а)): отдельного экрана нет, он сужает список
     * поверх активных фильтров.
     */
    data class Filter(
        val project: String? = null,
        val priority: String? = null,
        val status: String? = null,
        /** Одно значение из тегов задач: реестра тегов нет по ADR, значения собирает шторка. */
        val tag: String? = null,
        /** Одно из окон [DUES], а не дата. */
        val due: String? = null,
        val query: String = "",
    ) {
        /** Хоть один чип нажат или что-то введено — значит пусто «под фильтром», а не «вообще». */
        val active: Boolean
            get() =
                project != null ||
                    priority != null ||
                    status != null ||
                    tag != null ||
                    due != null ||
                    query.isNotBlank()

        fun of(facet: Facet): String? =
            when (facet) {
                Facet.PROJECT -> project
                Facet.PRIORITY -> priority
                Facet.STATUS -> status
                Facet.TAG -> tag
                Facet.DUE -> due
            }

        fun with(facet: Facet, value: String?): Filter =
            when (facet) {
                Facet.PROJECT -> copy(project = value)
                Facet.PRIORITY -> copy(priority = value)
                Facet.STATUS -> copy(status = value)
                Facet.TAG -> copy(tag = value)
                Facet.DUE -> copy(due = value)
            }

        /** Что остаётся от списка под этими чипами и этим поиском. */
        fun select(tasks: List<TaskFile.Task>, today: LocalDate): List<TaskFile.Task> {
            val text = query.trim()
            return tasks.filter { task ->
                (project == null || (task.project ?: NO_PROJECT) == project) &&
                    (priority == null || task.priority == priority) &&
                    (status == null || task.status == status) &&
                    (tag == null || tag in task.tags) &&
                    (due == null || inWindow(task, today)) &&
                    (text.isEmpty() || task.title.contains(text, ignoreCase = true))
            }
        }

        /**
         * Окно срока. «На неделе» — те же семь дней, которыми меряет [dueLabel] («до пт»): два
         * разных представления о неделе на одном экране разошлись бы молча. Незнакомое окно
         * (испорченное состояние из `rememberSaveable`) не сужает ничего — прятать от владельца
         * весь список из-за строки в bundle нельзя.
         */
        private fun inWindow(task: TaskFile.Task, today: LocalDate): Boolean =
            when (due) {
                DUE_TODAY -> task.due == today
                DUE_WEEK ->
                    task.due != null &&
                        !task.due.isBefore(today) &&
                        task.due.isBefore(today.plusDays(WEEK))
                DUE_OVERDUE -> isOverdue(task, today)
                DUE_NONE -> task.due == null
                else -> true
            }

        /**
         * Счётчики для шторки одного чипа. **Фасетные** (вердикт UX): свой фильтр из расчёта
         * исключён — иначе у всех невыбранных значений стоял бы ноль и шторка стала бы бесполезной.
         * Чужие чипы учитываются: счётчик обещает ровно то, что владелец увидит после выбора.
         *
         * Ключ `null` — строка «Все …»: сколько станет, если сбросить именно этот чип. Значения из
         * реестра, которых нет ни у одной задачи, остаются со счётчиком 0 — их не прячем.
         */
        fun counts(
            tasks: List<TaskFile.Task>,
            facet: Facet,
            values: List<String>,
            today: LocalDate,
        ): Map<String?, Int> {
            val rest = with(facet, null).select(tasks, today)
            return (listOf(null) + values).associateWith { value ->
                if (value == null) {
                    rest.size
                } else {
                    Filter().with(facet, value).select(rest, today).size
                }
            }
        }
    }

    fun open(tasks: List<TaskFile.Task>, today: LocalDate): List<TaskFile.Task> =
        tasks.filterNot { it.isDone }.sortedWith(order(today))

    /** Свежезакрытые сверху; закрытые без даты (миграция, правка руками) — в конец. */
    fun done(tasks: List<TaskFile.Task>, today: LocalDate): List<TaskFile.Task> {
        val (dated, undated) = tasks.filter { it.isDone }.partition { it.done != null }
        return dated
            .filter { !it.done!!.isBefore(today.minusDays(DONE_WINDOW_DAYS)) }
            .sortedByDescending { it.done } + undated
    }

    /** Счётчик открытых в шапке — один путь для обеих вкладок, без лишней сортировки. */
    fun openCount(tasks: List<TaskFile.Task>): Int = tasks.count { !it.isDone }

    /** В счётчик рубрики идут только закрытые с датой внутри окна. */
    fun doneCount(tasks: List<TaskFile.Task>, today: LocalDate): Int =
        done(tasks, today).count { it.done != null }

    /**
     * Показывать нечего вовсе. Закрытые в окне «Сделано за месяц» — тоже содержимое: при них «Задач
     * пока нет» было бы враньём экрана (вердикт UX).
     */
    fun nothingToShow(tasks: List<TaskFile.Task>, today: LocalDate): Boolean =
        openCount(tasks) == 0 && done(tasks, today).isEmpty()

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

/** Слово рубрики. Чужой приоритет из frontmatter слова не получает — врать «обычный» нельзя. */
internal fun priorityWord(priority: String): String =
    when (priority) {
        "P1" -> "высокий"
        "P2" -> "обычный"
        "P3" -> "низкий"
        else -> ""
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
