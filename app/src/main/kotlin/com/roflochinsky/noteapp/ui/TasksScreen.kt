package com.roflochinsky.noteapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roflochinsky.noteapp.pipeline.SyncStatus
import com.roflochinsky.noteapp.pipeline.TaskFile
import java.time.LocalDate

/**
 * Экран задач по борду 1 компа: рубрики приоритета, строка-документ с чекбоксом, свёрнутое
 * «Сделано», кнопка внизу. Карточек, бейджей и канбана в этом мире нет (DESIGN.md).
 *
 * В срезе Н1 экран только читает: чекбокс и «Новая задача» оживают срезом Н2.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(
    tasks: List<TaskFile.Task>,
    today: LocalDate,
    sync: SyncStatus,
    refreshing: Boolean,
    isRecording: Boolean,
    onTab: (Tab) -> Unit,
    onRefresh: () -> Unit,
    onRecord: () -> Unit,
    onSettings: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        SectionTabs(Tab.TASKS, TaskFilter.open(tasks, today).size, onTab, onSettings)
        SyncLine(sync, onSettings)
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f),
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = rememberPullToRefreshState(),
                    isRefreshing = refreshing,
                    modifier = Modifier.align(Alignment.TopCenter),
                    containerColor = DocPalette.Paper,
                    color = DocPalette.Blue,
                )
            },
        ) {
            TaskList(tasks, today)
        }
        if (isRecording) RecordBar(isRecording = true, onRecord = onRecord) else NewTaskBar()
    }
}

@Composable
private fun TaskList(tasks: List<TaskFile.Task>, today: LocalDate) {
    val groups = TaskFilter.byPriority(tasks, today)
    val done = TaskFilter.done(tasks, today)
    var doneOpen by rememberSaveable { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize()) {
        if (tasks.isEmpty()) {
            item { EmptyTasks() }
        } else if (groups.isEmpty()) {
            item { EmptyUnderState() }
        }
        groups.forEach { (priority, group) ->
            item(key = "prio-$priority") { PriorityRubric(priority) }
            items(group, key = { it.path }) { task ->
                TaskRow(task, today)
                HorizontalDivider(
                    color = DocPalette.Line,
                    modifier = Modifier.padding(horizontal = 22.dp),
                )
            }
        }
        if (done.isNotEmpty()) {
            item(key = "done-fold") {
                DoneFold(TaskFilter.doneCount(tasks, today), doneOpen) { doneOpen = !doneOpen }
            }
            if (doneOpen) {
                items(done, key = { "done-${it.path}" }) { task ->
                    TaskRow(task, today)
                    HorizontalDivider(
                        color = DocPalette.Line,
                        modifier = Modifier.padding(horizontal = 22.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PriorityRubric(priority: String) {
    Row(
        modifier = Modifier.padding(start = 22.dp, top = 16.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(priority, style = MaterialTheme.typography.labelMedium.copy(color = DocPalette.Ink))
        Text(priorityWord(priority).uppercase(), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun TaskRow(task: TaskFile.Task, today: LocalDate) {
    Row(
        // Отступ такой, чтобы видимый чекбокс встал на гуттер 22dp, а его тач-таргет остался 48dp.
        modifier = Modifier.fillMaxWidth().padding(start = 7.5.dp, end = 22.dp),
        verticalAlignment = Alignment.Top,
    ) {
        TaskCheckbox(task.isDone)
        Column(Modifier.padding(top = 13.dp, bottom = 14.dp)) {
            Text(
                task.title,
                style =
                    if (task.isDone) {
                        MaterialTheme.typography.titleMedium.copy(
                            color = DocPalette.Mut,
                            fontWeight = FontWeight.Normal,
                            textDecoration = TextDecoration.LineThrough,
                        )
                    } else {
                        MaterialTheme.typography.titleMedium
                    },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            TaskMeta(task, today)
        }
    }
}

/** Визуально 19dp по компу, тач-таргет — 48dp (правило доступности). */
@Composable
private fun TaskCheckbox(done: Boolean) {
    Box(Modifier.size(TOUCH.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier =
                Modifier.size(19.dp)
                    .then(
                        if (done) Modifier.background(DocPalette.Nav, RoundedCornerShape(6.dp))
                        else Modifier.border(1.5.dp, DocPalette.Mut, RoundedCornerShape(6.dp))
                    ),
            contentAlignment = Alignment.Center,
        ) {
            if (done) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = DocPalette.OnNav,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
    }
}

@Composable
private fun TaskMeta(task: TaskFile.Task, today: LocalDate) {
    val overdue = TaskFilter.isOverdue(task, today)
    val plain = buildList {
        task.project?.let { add(it) }
        if (task.status == TaskFile.STATUS_IN_PROGRESS) add("в работе")
        task.tags.forEach { add("#$it") }
    }
    val mono = buildList {
        if (!overdue && !task.isDone) task.due?.let { add(dueLabel(it, today)) }
        if (task.subtasks.isNotEmpty()) {
            add("${task.subtasks.count { it.done }}/${task.subtasks.size}")
        }
    }
    if (plain.isEmpty() && mono.isEmpty() && !overdue) return
    Row(
        modifier = Modifier.padding(top = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (overdue) {
            Text(
                overdueLabel(task.due!!, today),
                style = MaterialTheme.typography.bodySmall.copy(color = DocPalette.Amber),
            )
        }
        mono.forEach { Text(it, style = MaterialTheme.typography.labelMedium) }
        plain.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun DoneFold(count: Int, open: Boolean, onToggle: () -> Unit) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 22.dp)
                .height(TOUCH.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            if (open) Icons.Filled.KeyboardArrowDown
            else Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = DocPalette.Mut,
            modifier = Modifier.size(18.dp),
        )
        Text("Сделано за месяц · $count", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun EmptyTasks() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        EmptyIllustration()
        Text("Задач пока нет", style = MaterialTheme.typography.titleMedium)
        Text(
            "Скажи о задаче в записи — Claude достанет её сам.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 40.dp),
        )
    }
}

@Composable
private fun EmptyUnderState() {
    Text(
        "Под этот фильтр ничего не подошло",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 24.dp),
    )
}

/** Заглушка среза Н1: создание задачи приходит следующим срезом. */
@Composable
private fun NewTaskBar() {
    Column(
        modifier =
            Modifier.fillMaxWidth().padding(22.dp, 12.dp, 22.dp, 8.dp).navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Button(
            onClick = {},
            enabled = false,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(14.dp),
            colors =
                ButtonDefaults.buttonColors(
                    disabledContainerColor = DocPalette.Paper2,
                    disabledContentColor = DocPalette.Mut,
                ),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Box(Modifier.size(width = 10.dp, height = 1.dp))
            Text("Новая задача")
        }
        Text(
            "создание задачи — следующий срез",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

private const val TOUCH = 48
