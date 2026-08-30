package com.roflochinsky.noteapp.ui

import com.roflochinsky.noteapp.pipeline.FeedItem
import java.time.LocalDate

/**
 * Чипы и поиск ленты заметок — то же, чем [TaskFilter] служит списку задач, но по своим осям: тип,
 * персона, проект, тег, дата (порядок компа, борд 5). Сегодняшняя дата приходит параметром.
 *
 * Поиск — вторая половина решения владельца 2026-08-26 (а): по заголовку И по превью заметки (у
 * задач ищется заголовок, срез Н3). Отдельного экрана нет, поле разворачивается в шапке.
 *
 * ponytail: чип держит одно значение, а не набор. Комп рисует «Персона: Дима», и «Дима ИЛИ Оля» ни
 * в компе, ни в запросе владельца не просили; понадобится — здесь меняется тип поля, а не логика.
 */
object NoteFilter {

    /** Значения чипа «Дата»: окна от сегодня, а не календарные периоды. */
    const val DAY = "day"
    const val WEEK = "week"
    const val MONTH = "month"

    val DATES = listOf(DAY, WEEK, MONTH)

    private val WINDOW = mapOf(DAY to 0L, WEEK to 7L, MONTH to 30L)

    /** Типы заметки — фиксированный список ADR (CONTEXT.md); папка репо у каждого своя. */
    val TYPES = listOf("встреча", "идея", "задача", "личное", "другое")

    enum class Facet {
        TYPE,
        PERSON,
        PROJECT,
        TAG,
        DATE,
    }

    data class Filter(
        val project: String? = null,
        val type: String? = null,
        val person: String? = null,
        val tag: String? = null,
        val date: String? = null,
        val query: String = "",
    ) {
        val active: Boolean
            get() =
                project != null ||
                    type != null ||
                    person != null ||
                    tag != null ||
                    date != null ||
                    query.isNotBlank()

        fun of(facet: Facet): String? =
            when (facet) {
                Facet.TYPE -> type
                Facet.PERSON -> person
                Facet.PROJECT -> project
                Facet.TAG -> tag
                Facet.DATE -> date
            }

        fun with(facet: Facet, value: String?): Filter =
            when (facet) {
                Facet.TYPE -> copy(type = value)
                Facet.PERSON -> copy(person = value)
                Facet.PROJECT -> copy(project = value)
                Facet.TAG -> copy(tag = value)
                Facet.DATE -> copy(date = value)
            }

        /** Что остаётся от ленты под этими чипами и этим поиском. */
        fun select(feed: List<FeedItem>, today: LocalDate): List<FeedItem> {
            val text = query.trim()
            return feed.filter { item ->
                (type == null || item.type == type) &&
                    (person == null || person in item.participants) &&
                    (project == null || (item.project ?: TaskFilter.NO_PROJECT) == project) &&
                    (tag == null || tag in item.tags) &&
                    (date == null || within(item, today)) &&
                    (text.isEmpty() || matches(item, text))
            }
        }

        /**
         * Счётчики шторки одного чипа — фасетные (вердикт UX): свой фильтр из расчёта исключён,
         * чужие учитываются. Ключ `null` — строка «Все …»: сколько станет, если сбросить этот чип.
         */
        fun counts(
            feed: List<FeedItem>,
            today: LocalDate,
            facet: Facet,
            values: List<String>,
        ): Map<String?, Int> {
            val rest = with(facet, null).select(feed, today)
            return (listOf(null) + values).associateWith { value ->
                if (value == null) rest.size
                else Filter().with(facet, value).select(rest, today).size
            }
        }

        /** Заметка без разобранного времени под датным чипом не показывается — врать нечем. */
        private fun within(item: FeedItem, today: LocalDate): Boolean {
            val day = item.time?.toLocalDate() ?: return false
            val days = WINDOW[date] ?: return true
            return !day.isBefore(today.minusDays(days))
        }

        private fun matches(item: FeedItem, text: String): Boolean =
            item.title.contains(text, ignoreCase = true) ||
                item.preview.contains(text, ignoreCase = true)
    }
}
