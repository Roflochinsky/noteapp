package com.roflochinsky.noteapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    projects: List<String>,
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
    // Состояние чипов и поиска переживает поворот экрана, но не перезапуск (решение LLD-17):
    // фильтр — это «что я сейчас разглядываю», а не настройка.
    var filter by rememberSaveable(stateSaver = FilterSaver) { mutableStateOf(TaskFilter.Filter()) }
    var searching by rememberSaveable { mutableStateOf(false) }
    val shown = filter.select(tasks)
    // Владелец ушёл с экрана, не дождавшись конца окна отмены, — отправку планируем здесь
    // (находка Д3). Эффект висит на экране, а не на id операции: `DisposableEffect(id)` при смене
    // id диспозился и тянул ВСЮ очередь, включая ту операцию, для которой снекбар ещё предлагал
    // «Отменить», а `queue.cancel` к тому моменту уже бессилен (находка повторного ревью).
    //
    // ponytail: поворот экрана composition тоже уничтожает, и правка уезжает сразу — окно отмены
    // пересоздания активити не переживает. Так же ведёт себя и холодный старт: `store()` при
    // непустой очереди заводит воркер сам. Правка при этом не теряется — она именно уходит.
    DisposableEffect(Unit) { onDispose { onFlush() } }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            SectionTabs(
                active = Tab.TASKS,
                tasksCount = TaskFilter.openCount(tasks),
                onTab = onTab,
                onSettings = onSettings,
                query = filter.query.takeIf { searching },
                onQuery = { text ->
                    searching = text != null
                    filter = filter.copy(query = text.orEmpty())
                },
            )
            FilterChips(tasks, projects, filter) { filter = it }
            SyncLine(sync, onSettings)
            notice?.let { DivergenceLine(it, onNotice) }
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
                TaskList(
                    shown,
                    filter.active,
                    today,
                    pending,
                    onTask,
                    {
                        filter = TaskFilter.Filter()
                        searching = false
                    },
                ) { task ->
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
            // Окно отмены закрылось по таймеру — отправляем. Лишний прогон после «Отменить»
            // безвреден: очередь пуста, воркер сразу отдаёт success.
            LaunchedEffect(id) {
                delay(UNDO_MS)
                undo = null
                onFlush()
            }
            Snack("Сделано", "Отменить", Modifier.align(Alignment.BottomCenter)) {
                onCancel(id)
                undo = null
                onFlush()
            }
        }
    }
}

/**
 * Расхождение по 409 — непрерывающая плашка под шапкой, рядом со строкой синка: не модалка и не
 * `Toast`, палитра — янтарь внимания (`err` в этом мире только на кнопке «Удалить»). Владелец
 * дочитывает её сам или ждёт, пока она уйдёт; список под ней живой.
 *
 * Таймер живёт внутри плашки: пока владелец в деталке задачи, она не нарисована — значит и не
 * истекает, и сообщение дождётся возвращения на список.
 */
@Composable
internal fun DivergenceLine(text: String, onDone: () -> Unit) {
    LaunchedEffect(text) {
        delay(NOTICE_MS)
        onDone()
    }
    Box(
        modifier =
            Modifier.fillMaxWidth()
                .clickable(onClick = onDone)
                .heightIn(min = TOUCH.dp)
                .padding(horizontal = 22.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall.copy(color = DocPalette.Amber))
    }
}

/**
 * Снекбар с действием — единственная модалка-не-модалка внизу; сообщения без действия — плашкой.
 */
@Composable
private fun Snack(text: String, action: String, modifier: Modifier, onAction: () -> Unit) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(16.dp)
                .navigationBarsPadding()
                .background(DocPalette.Nav, RoundedCornerShape(12.dp))
                .heightIn(min = TOUCH.dp)
                .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall.copy(color = DocPalette.OnNav),
            modifier = Modifier.weight(1f),
        )
        Text(
            action.uppercase(),
            style =
                MaterialTheme.typography.labelSmall.copy(
                    color = DocPalette.OnNav,
                    fontWeight = FontWeight.Bold,
                ),
            // Тач-таргет действия — вся высота снекбара (не меньше 48dp), а не кегль подписи.
            modifier =
                Modifier.clickable(onClick = onAction)
                    .fillMaxHeight()
                    .padding(start = 16.dp)
                    .wrapContentHeight(),
        )
    }
}

@Composable
private fun TaskList(
    tasks: List<TaskFile.Task>,
    filtered: Boolean,
    today: LocalDate,
    pending: (String) -> Boolean,
    onTask: (String) -> Unit,
    onReset: () -> Unit,
    onToggle: (TaskFile.Task) -> Unit,
) {
    val groups = TaskFilter.byPriority(tasks, today)
    val done = TaskFilter.done(tasks, today)
    var doneOpen by rememberSaveable { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize()) {
        // Пусто ровно тогда, когда показывать нечего вовсе: при свёрнутой рубрике «Сделано»
        // «Задач пока нет» было бы враньём экрана. Под активным фильтром это другое пустое
        // состояние (вердикт UX): задачи есть, просто под чипы не подошли.
        if (TaskFilter.nothingToShow(tasks, today)) {
            item { if (filtered) EmptyFiltered(onReset) else EmptyTasks() }
        }
        groups.forEach { (priority, group) ->
            item(key = "prio-$priority") { PriorityRubric(priority) }
            itemsIndexed(group, key = { _, task -> task.path }) { i, task ->
                // Комп: `.trow + .trow{border-top}` — линия между соседями, после последней нет.
                // Линия full-bleed: в компе border-top висит на самой строке с её паддингом,
                // и `DESIGN.md` требует «full-bleed строки, разделённые hairline 1px».
                if (i > 0) {
                    HorizontalDivider(color = DocPalette.Line)
                }
                TaskRow(task, today, pending(task.path), onTask, onToggle)
            }
        }
        if (done.isNotEmpty()) {
            item(key = "done-fold") {
                DoneFold(TaskFilter.doneCount(tasks, today), doneOpen) { doneOpen = !doneOpen }
            }
            if (doneOpen) {
                itemsIndexed(done, key = { _, task -> "done-${task.path}" }) { i, task ->
                    if (i > 0) {
                        HorizontalDivider(color = DocPalette.Line)
                    }
                    TaskRow(task, today, pending(task.path), onTask, onToggle)
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
        val word = priorityWord(priority)
        if (word.isNotEmpty()) Text(word.uppercase(), style = MaterialTheme.typography.labelSmall)
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
        TaskCheckbox(task.isDone, task.title) { onToggle(task) }
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

/**
 * Визуально 19dp по компу, тач-таргет — 48dp (правило доступности). `toggleable` с ролью — чтобы
 * TalkBack читал «флажок, отмечено», а не безымянную кнопку (вердикт UX про семантику).
 *
 * Описание называет ЗАДАЧУ и только её: состояние к нему добавляет сама роль `Checkbox`. Прежнее
 * «сделана: $title» стояло и на неотмеченном флажке — TalkBack читал его как утверждение, что
 * задача сделана, то есть врал ровно там, где владелец видеть не может (находка Д11).
 */
@Composable
private fun TaskCheckbox(done: Boolean, title: String, onToggle: () -> Unit) {
    Box(
        Modifier.size(TOUCH.dp)
            .toggleable(value = done, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics { contentDescription = title },
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

/**
 * Мета-строка борда 1: проект → просрочка/срок → подзадачи → «в работе» → теги, между элементами
 * точка-разделитель. Порядок и стиль элемента хранятся вместе — один список, а не «моно» и «текст»
 * двумя кучами, иначе порядок компа собрать нельзя.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskMeta(task: TaskFile.Task, today: LocalDate, pending: Boolean) {
    val overdue = TaskFilter.isOverdue(task, today)
    val text = MaterialTheme.typography.bodySmall
    val mono = MaterialTheme.typography.labelMedium
    val parts =
        buildList<@Composable () -> Unit> {
            // Состояние записи важнее полей: «в очереди» стоит первым и всегда янтарём (вердикт
            // UX).
            if (pending) {
                add { Text("в очереди", style = text.copy(color = DocPalette.Amber)) }
            }
            task.project?.let { project -> add { Text(project, style = text) } }
            if (overdue) {
                add { OverdueLabel(task.due!!, today) }
            } else if (!task.isDone) {
                task.due?.let { due -> add { Text(dueLabel(due, today), style = mono) } }
            }
            if (task.subtasks.isNotEmpty()) {
                val progress = "${task.subtasks.count { it.done }}/${task.subtasks.size}"
                add { Text(progress, style = mono) }
            }
            if (task.status == TaskFile.STATUS_IN_PROGRESS) add { Text("в работе", style = text) }
            task.tags.forEach { tag -> add { Text("#$tag", style = text) } }
        }
    if (parts.isEmpty()) return
    // Мета длиннее строки переносится (комп: flex-wrap), а не режется и не ломает раскладку.
    FlowRow(
        modifier = Modifier.padding(top = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        parts.forEachIndexed { i, part ->
            if (i > 0) Text("·", style = mono)
            part()
        }
    }
}

/**
 * Просрочка по компу: часы 13dp и полужирный янтарь — она должна кричать громче остальной меты.
 *
 * ponytail: часы рисуются вручную по пути из компа — ради одной иконки тащить
 * `material-icons-extended` (тысячи векторов в APK) не стоит.
 */
@Composable
private fun OverdueLabel(due: LocalDate, today: LocalDate) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(CLOCK.dp)) {
            val u = size.minDimension / VIEWBOX // комп рисует в вьюбоксе 24
            val stroke = 2f * u
            val centre = Offset(12f * u, 12f * u)
            drawCircle(DocPalette.Amber, radius = 8.5f * u, center = centre, style = Stroke(stroke))
            drawLine(DocPalette.Amber, Offset(12f * u, 7.5f * u), centre, stroke, StrokeCap.Round)
            drawLine(DocPalette.Amber, centre, Offset(15f * u, 14f * u), stroke, StrokeCap.Round)
        }
        Text(
            overdueLabel(due, today),
            style =
                MaterialTheme.typography.bodySmall.copy(
                    color = DocPalette.Amber,
                    fontWeight = FontWeight.SemiBold,
                ),
        )
    }
}

@Composable
private fun DoneFold(count: Int, open: Boolean, onToggle: () -> Unit) {
    Column {
        // Комп: `.fold{border-top}` — над рубрикой «Сделано» линия есть всегда, в отличие от
        // строк задач (там линия только между соседями).
        HorizontalDivider(color = DocPalette.Line)
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
}

/**
 * Пусто под фильтром — не то же, что «задач нет вовсе»: иллюстрации здесь нет (она про пустой
 * трекер, а он не пуст), зато есть выход одним тапом.
 */
@Composable
private fun EmptyFiltered(onReset: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 60.dp, start = 40.dp, end = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Под этот фильтр ничего не подошло", style = MaterialTheme.typography.titleMedium)
        Text(
            "Сбросить фильтры",
            style = MaterialTheme.typography.bodySmall.copy(color = DocPalette.Blue),
            modifier =
                Modifier.clickable(onClick = onReset).heightIn(min = TOUCH.dp).wrapContentHeight(),
        )
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
            Spacer(Modifier.width(10.dp))
            Text("Новая задача")
        }
        Text(
            "задача станет файлом в tasks/ и уйдёт коммитом",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * Фильтр в `rememberSaveable`: полей четыре, и класть их четырьмя строками — это четыре ключа,
 * которые легко разъедутся. `listSaver` кладёт ровно то, что есть в [TaskFilter.Filter].
 */
private val FilterSaver =
    listSaver<TaskFilter.Filter, String?>(
        save = { listOf(it.project, it.priority, it.status, it.query) },
        restore = { TaskFilter.Filter(it[0], it[1], it[2], it[3].orEmpty()) },
    )

private const val CLOCK = 13
private const val VIEWBOX = 24f
private const val UNDO_MS = 5000L
private const val NOTICE_MS = 6000L
