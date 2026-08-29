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
import com.roflochinsky.noteapp.pipeline.TaskFile

/**
 * Чипы-фильтры экрана задач по компу v2 (борды 1 и 2): «Проект», «Приоритет», «Статус» одной
 * горизонтальной строкой под шапкой, выбор — шторкой со счётчиками.
 *
 * Счётчики **фасетные** (вердикт UX): свой чип из расчёта исключён, чужие учитываются, а значения
 * реестра без задач остаются со счётчиком 0 — иначе шторка врёт про то, что покажет список.
 *
 * ponytail: шторка — общий [ChoiceSheet] среза Н2, своей копии строк выбора здесь нет. Цена — её
 * заголовок набран прописными (комп для шторок рисует `.title`); расхождение унаследовано и живёт в
 * `TaskSheets.kt`, который в этом срезе за другим агентом (`bd nikitatrubaev-0rk.26`).
 */
@Composable
fun FilterChips(
    tasks: List<TaskFile.Task>,
    projects: List<String>,
    filter: TaskFilter.Filter,
    onFilter: (TaskFilter.Filter) -> Unit,
) {
    var open by remember { mutableStateOf<TaskFilter.Facet?>(null) }
    LazyRow(
        modifier = Modifier.padding(top = 10.dp, bottom = 12.dp),
        contentPadding = PaddingValues(horizontal = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(TaskFilter.Facet.entries.toList(), key = { it.name }) { facet ->
            val value = filter.of(facet)
            Chip(
                label = value?.let { label(facet, it) } ?: facetName(facet),
                active = value != null,
                onClick = { open = facet },
                onReset = { onFilter(filter.with(facet, null)) },
            )
        }
    }
    open?.let { facet ->
        val values = values(facet, projects, tasks)
        ChoiceSheet(
            title = facetName(facet),
            choices =
                listOf(Choice(null, all(facet))) + values.map { Choice(it, label(facet, it)) },
            selected = filter.of(facet),
            counts = filter.counts(tasks, facet, values),
            onDismiss = { open = null },
        ) { picked ->
            onFilter(filter.with(facet, picked))
            open = null
        }
    }
}

/**
 * Комп: контур 1px `line`, радиус 9dp, паддинг 7×12, текст 0.82rem `mut`, шеврон 12dp; активный —
 * заливка `blue-soft`, контур и текст `blue`, вес 600, шеврон сменился крестиком сброса.
 *
 * Крестик — свой кликабельный узел ВНУТРИ кликабельного чипа: настоящее попадание перебивает
 * near-hit соседа (разбор `NodeCoordinator.hitTest` в круге 4 среза Н2), поэтому тап по имени
 * открывает шторку, а тап по крестику сбрасывает. Узел крестика — 12×12dp (замер Robolectric при
 * чипе 85×29dp), до пальца его добирает near-hit, и имя он при этом не съедает.
 */
@Composable
private fun Chip(label: String, active: Boolean, onClick: () -> Unit, onReset: () -> Unit) {
    val shape = RoundedCornerShape(9.dp)
    Row(
        modifier =
            Modifier.clip(shape) // рипл по скруглению, а не прямоугольником
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

private fun facetName(facet: TaskFilter.Facet): String =
    when (facet) {
        TaskFilter.Facet.PROJECT -> "Проект"
        TaskFilter.Facet.PRIORITY -> "Приоритет"
        TaskFilter.Facet.STATUS -> "Статус"
    }

/** Строка сброса в шторке: она же показывает, сколько станет без этого чипа. */
private fun all(facet: TaskFilter.Facet): String =
    when (facet) {
        TaskFilter.Facet.PROJECT -> "Все проекты"
        TaskFilter.Facet.PRIORITY -> "Все приоритеты"
        TaskFilter.Facet.STATUS -> "Все статусы"
    }

/**
 * Значения чипа. Проекты — из реестра `projects.md` плюс те, что уже стоят в задачах: реестр
 * пополняется руками, и задача с незнакомым проектом не должна выпадать из фильтра. «Без проекта» —
 * последней строкой, как в компе.
 */
private fun values(
    facet: TaskFilter.Facet,
    projects: List<String>,
    tasks: List<TaskFile.Task>,
): List<String> =
    when (facet) {
        TaskFilter.Facet.PROJECT ->
            (projects + tasks.mapNotNull { it.project }).distinct() + TaskFilter.NO_PROJECT
        TaskFilter.Facet.PRIORITY -> TaskFilter.PRIORITIES
        TaskFilter.Facet.STATUS -> TaskFilter.STATUSES
    }

private fun label(facet: TaskFilter.Facet, value: String): String =
    when (facet) {
        TaskFilter.Facet.PROJECT -> if (value == TaskFilter.NO_PROJECT) "Без проекта" else value
        TaskFilter.Facet.PRIORITY ->
            priorityWord(value).let { if (it.isEmpty()) value else "$value · $it" }
        TaskFilter.Facet.STATUS -> statusWord(value)
    }

/**
 * Слова статуса во множественном числе: чип и шторка говорят о наборе задач, а сегмент деталки
 * («Открыта», «В работе», «Сделана») — об одной. Значения при этом одни и те же, из [TaskFile].
 */
internal fun statusWord(status: String): String =
    when (status) {
        TaskFile.STATUS_OPEN -> "Открытые"
        TaskFile.STATUS_IN_PROGRESS -> "В работе"
        TaskFile.STATUS_DONE -> "Сделанные"
        else -> status
    }

private const val CHEVRON = 12
