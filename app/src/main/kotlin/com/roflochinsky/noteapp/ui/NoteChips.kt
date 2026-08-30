package com.roflochinsky.noteapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.roflochinsky.noteapp.pipeline.FeedItem
import java.time.LocalDate

/**
 * Чипы-фильтры ленты по компу v2 (борд 5): «Тип», «Персона», «Проект», «Тег», «Дата» одной строкой
 * под шапкой, выбор — шторкой со счётчиками. Порядок чипов — из компа, а не по алфавиту оси.
 *
 * Счётчики фасетные (вердикт UX), как и у задач: свой чип из расчёта исключён, значения реестра без
 * заметок остаются с нулём.
 *
 * ponytail: своя копия чипа рядом с [FilterChips] — не по вкусу, а по границе срезов: чип задач
 * этой ночью пишет параллельный агент (`bd nikitatrubaev-0rk.26`), и заходить в его файл нельзя.
 * Свести обе копии в один компонент — `bd nikitatrubaev-0rk.29`, после слияния срезов.
 */
@Composable
fun NoteChips(
    feed: List<FeedItem>,
    people: List<String>,
    projects: List<String>,
    today: LocalDate,
    filter: NoteFilter.Filter,
    onFilter: (NoteFilter.Filter) -> Unit,
) {
    var open by remember { mutableStateOf<NoteFilter.Facet?>(null) }
    LazyRow(
        modifier = Modifier.padding(top = 10.dp, bottom = 12.dp),
        contentPadding = PaddingValues(horizontal = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(NoteFilter.Facet.entries.toList(), key = { it.name }) { facet ->
            val value = filter.of(facet)
            Chip(
                label = value?.let { active(facet, it) } ?: facetName(facet),
                active = value != null,
                onClick = { open = facet },
                onReset = { onFilter(filter.with(facet, null)) },
            )
        }
    }
    open?.let { facet ->
        val values = values(facet, people, projects, feed)
        ChoiceSheet(
            title = facetName(facet),
            choices =
                listOf(Choice(null, all(facet))) + values.map { Choice(it, label(facet, it)) },
            selected = filter.of(facet),
            counts = filter.counts(feed, today, facet, values),
            onDismiss = { open = null },
        ) { picked ->
            onFilter(filter.with(facet, picked))
            open = null
        }
    }
}

/** Комп: контур 1px `line`, радиус 9dp, паддинг 7×12, шеврон 12dp; активный — `blue-soft`. */
@Composable
private fun Chip(label: String, active: Boolean, onClick: () -> Unit, onReset: () -> Unit) {
    val shape = RoundedCornerShape(9.dp)
    Row(
        modifier =
            Modifier.clip(shape)
                .then(if (active) Modifier.background(DocPalette.BlueSoft) else Modifier)
                .border(1.dp, if (active) DocPalette.Blue else DocPalette.Line, shape)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            label,
            style =
                MaterialTheme.typography.bodySmall.copy(
                    color = if (active) DocPalette.Blue else DocPalette.Mut,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                ),
        )
        Icon(
            if (active) Icons.Filled.Close else Icons.Filled.KeyboardArrowDown,
            contentDescription = if (active) "Сбросить $label" else null,
            tint = if (active) DocPalette.Blue else DocPalette.Mut,
            modifier =
                Modifier.size(CHEVRON.dp)
                    .then(if (active) Modifier.clickable(onClick = onReset) else Modifier),
        )
    }
}

private fun facetName(facet: NoteFilter.Facet): String =
    when (facet) {
        NoteFilter.Facet.TYPE -> "Тип"
        NoteFilter.Facet.PERSON -> "Персона"
        NoteFilter.Facet.PROJECT -> "Проект"
        NoteFilter.Facet.TAG -> "Тег"
        NoteFilter.Facet.DATE -> "Дата"
    }

/**
 * Надпись активного чипа. Комп называет ось только там, где значение само за себя не говорит:
 * «Персона: Дима» (борд 5) — а «встреча», «tgsum», «#релиз» и «За неделю» понятны и без подписи.
 */
private fun active(facet: NoteFilter.Facet, value: String): String =
    if (facet == NoteFilter.Facet.PERSON) "Персона: $value" else label(facet, value)

/** Строка сброса в шторке: она же показывает, сколько станет без этого чипа. */
private fun all(facet: NoteFilter.Facet): String =
    when (facet) {
        NoteFilter.Facet.TYPE -> "Все типы"
        NoteFilter.Facet.PERSON -> "Все персоны"
        NoteFilter.Facet.PROJECT -> "Все проекты"
        NoteFilter.Facet.TAG -> "Все теги"
        NoteFilter.Facet.DATE -> "За всё время"
    }

/**
 * Значения чипа. Реестры (`people.md`, `projects.md`) идут первыми в своём порядке, следом — то,
 * что Claude уже проставил в заметках, но в реестр не попало: иначе заметка с незнакомой персоной
 * выпала бы из фильтра. Типы — фиксированный список ADR, теги реестра не имеют вовсе.
 */
private fun values(
    facet: NoteFilter.Facet,
    people: List<String>,
    projects: List<String>,
    feed: List<FeedItem>,
): List<String> =
    when (facet) {
        NoteFilter.Facet.TYPE -> (NoteFilter.TYPES + feed.mapNotNull { it.type }).distinct()
        NoteFilter.Facet.PERSON -> (people + feed.flatMap { it.participants }).distinct()
        NoteFilter.Facet.PROJECT ->
            (projects + feed.mapNotNull { it.project }).distinct() + TaskFilter.NO_PROJECT
        NoteFilter.Facet.TAG -> feed.flatMap { it.tags }.distinct()
        NoteFilter.Facet.DATE -> NoteFilter.DATES
    }

private fun label(facet: NoteFilter.Facet, value: String): String =
    when (facet) {
        NoteFilter.Facet.PROJECT -> if (value == TaskFilter.NO_PROJECT) "Без проекта" else value
        NoteFilter.Facet.TAG -> "#$value"
        NoteFilter.Facet.DATE -> dateWord(value)
        else -> value
    }

private fun dateWord(value: String): String =
    when (value) {
        NoteFilter.DAY -> "Сегодня"
        NoteFilter.WEEK -> "За неделю"
        NoteFilter.MONTH -> "За месяц"
        else -> value
    }

private const val CHEVRON = 12
