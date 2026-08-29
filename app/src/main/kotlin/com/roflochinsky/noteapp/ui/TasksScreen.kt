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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import kotlinx.coroutines.delay

/**
 * Экран задач по борду 1 компа: рубрики приоритета, строка-документ с чекбоксом, свёрнутое
 * «Сделано», кнопка внизу. Карточек, бейджей и канбана в этом мире нет (DESIGN.md).
 *
 * Чекбокс бинарен: `done ↔ open`, «в работе» ставится только сегментом в деталке. После тапа —
 * снекбар «Сделано · Отменить» на 5 секунд; отправка стартует, когда он закроется.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(
    tasks: List<TaskFile.Task>,
    today: LocalDate,
    sync: SyncStatus,
    refreshing: Boolean,
    isRecording: Boolean,
    notice: String?,
    pending: (String) -> Boolean,
    onTab: (Tab) -> Unit,
    onRefresh: () -> Unit,
    onRecord: () -> Unit,
    onSettings: () -> Unit,
    onTask: (String) -> Unit,
    onNewTask: () -> Unit,
    onToggle: (TaskFile.Task) -> String,
    onCancel: (String) -> Unit,
    onFlush: () -> Unit,
    onNotice: () -> Unit,
) {
    val pull = rememberPullToRefreshState()
    var undo by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        SectionTabs(Tab.TASKS, TaskFilter.open(tasks, today).size, onTab, onSettings)
        SyncLine(sync, onSettings)
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f),
            state = pull,
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pull,
                    isRefreshing = refreshing,
                    modifier = Modifier.align(Alignment.TopCenter),
                    containerColor = DocPalette.Paper,
                    color = DocPalette.Blue,
                )
            },
        ) {
            TaskList(tasks, today, pending, onTask) { task ->
                val id = onToggle(task)
                undo = if (task.isDone) null else id
                if (task.isDone) onFlush()
            }
        }
        if (isRecording) {
            RecordBar(isRecording = true, onRecord = onRecord)
        } else {
            NewTaskBar(onNewTask)
        }
    }
        undo?.let { id ->
            // Окно отмены: операция уже в журнале, но воркер стартует, когда снекбар закроется.
            LaunchedEffect(id) {
                delay(UNDO_MS)
                undo = null
                onFlush()
            }
            Snack("Сделано", "Отменить", Modifier.align(Alignment.BottomCenter)) {
                onCancel(id)
                undo = null
            }
        }
        notice?.let {
            LaunchedEffect(it) {
                delay(NOTICE_MS)
                onNotice()
            }
            Snack(it, null, Modifier.align(Alignment.BottomCenter)) { onNotice() }
        }
    }
}

/** Единственная поверхность коротких сообщений: снекбар внизу, без модалок и тостов. */
@Composable
private fun Snack(text: String, action: String?, modifier: Modifier, onAction: () -> Unit) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(16.dp)
                .navigationBarsPadding()
                .background(DocPalette.Nav, RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall.copy(color = DocPalette.OnNav),
            modifier = Modifier.weight(1f),
        )
        if (action != null) {
            Text(
                action.uppercase(),
                style =
                    MaterialTheme.typography.labelSmall.copy(
                        color = DocPalette.OnNav,
                        fontWeight = FontWeight.Bold,
                    ),
                modifier = Modifier.clickable(onClick = onAction).padding(start = 16.dp),
            )
        }
    }
}

@Composable
private fun TaskList(
    tasks: List<TaskFile.Task>,
    today: LocalDate,
    pending: (String) -> Boolean,
    onTask: (String) -> Unit,
    onToggle: (TaskFile.Task) -> Unit,
) {
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
                TaskRow(task, today, pending(task.path), onTask, onToggle)
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
                    TaskRow(task, today, pending(task.path), onTask, onToggle)
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
private fun TaskRow(
    task: TaskFile.Task,
    today: LocalDate,
    pending: Boolean,
    onTask: (String) -> Unit,
    onToggle: (TaskFile.Task) -> Unit,
) {
    Row(
        // Отступ такой, чтобы видимый чекбокс встал на гуттер 22dp, а его тач-таргет остался 48dp.
        modifier = Modifier.fillMaxWidth().padding(start = 7.5.dp, end = 22.dp),
        verticalAlignment = Alignment.Top,
    ) {
        TaskCheckbox(task.isDone) { onToggle(task) }
        Column(
            Modifier.clickable { onTask(task.path) }
                .fillMaxWidth()
                .padding(top = 13.dp, bottom = 14.dp)
        ) {
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
            TaskMeta(task, today, pending)
        }
    }
}

/** Визуально 19dp по компу, тач-таргет — 48dp (правило доступности). */
@Composable
private fun TaskCheckbox(done: Boolean, onToggle: () -> Unit) {
    Box(
        Modifier.size(TOUCH.dp).clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
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
private fun TaskMeta(task: TaskFile.Task, today: LocalDate, pending: Boolean) {
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
    if (plain.isEmpty() && mono.isEmpty() && !overdue && !pending) return
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
        if (pending) {
            Text(
                "в очереди",
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

@Composable
private fun NewTaskBar(onNewTask: () -> Unit) {
    Column(
        modifier =
            Modifier.fillMaxWidth().padding(22.dp, 12.dp, 22.dp, 8.dp).navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Button(
            onClick = onNewTask,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(14.dp),
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = DocPalette.Nav,
                    contentColor = DocPalette.OnNav,
                ),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Box(Modifier.size(width = 10.dp, height = 1.dp))
            Text("Новая задача")
        }
    }
}

private const val TOUCH = 48
private const val UNDO_MS = 5000L
private const val NOTICE_MS = 6000L
