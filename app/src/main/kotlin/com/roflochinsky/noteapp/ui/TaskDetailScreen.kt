package com.roflochinsky.noteapp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roflochinsky.noteapp.pipeline.Edit
import com.roflochinsky.noteapp.pipeline.TaskFile
import java.time.LocalDate

/**
 * Деталка задачи по борду 3 компа: сегмент статуса, поля-строки, подзадачи-чекбоксы, ссылка на
 * заметку-источник, статус-строка и ряд действий внизу.
 *
 * Скролл-контракт (вердикт UX): шапка и сегмент зафиксированы, поля и подзадачи скроллятся,
 * статус-строка и действия прижаты снизу вне скролла.
 */
@Composable
fun TaskDetailScreen(
    task: TaskFile.Task,
    projects: List<String>,
    pending: Boolean,
    today: LocalDate,
    onEdit: (Edit) -> Unit,
    onStatus: (String) -> Unit,
    onDelete: () -> Unit,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
) {
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var editingTitle by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Header(
            task = task,
            editing = editingTitle,
            onEditing = { editingTitle = it },
            onTitle = { onEdit(Edit.SetTitle(it)) },
            onBack = onBack,
        )
        StatusSegment(task.status, onStatus)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Fields(task, today, onEdit) { sheet = it }
            Subtasks(task, onEdit) { sheet = Sheet.SUBTASK }
            task.source?.let { Source(it, onOpen) }
        }
        StatusLine(task.path, pending)
        Actions(onDelete = { sheet = Sheet.DELETE }, onGithub = { onOpen(task.path) })
    }
    TaskSheet(sheet, task, projects, onEdit, onDelete) { sheet = null }
}

private enum class Sheet {
    PROJECT,
    PRIORITY,
    DUE,
    TAG,
    SUBTASK,
    DELETE,
}

@Composable
private fun TaskSheet(
    sheet: Sheet?,
    task: TaskFile.Task,
    projects: List<String>,
    onEdit: (Edit) -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    when (sheet) {
        null -> Unit
        Sheet.PROJECT ->
            ProjectSheet(projects, task.project, onClose) { onEdit(Edit.SetField("project", it)) }
        Sheet.PRIORITY ->
            ChoiceSheet(
                title = "Приоритет",
                choices = TaskFilter.PRIORITIES.map { Choice(it, "$it · ${priorityWord(it)}") },
                selected = task.priority,
                onDismiss = onClose,
            ) {
                onEdit(Edit.SetField("priority", it ?: TaskFile.PRIORITY_DEFAULT))
                onClose()
            }
        Sheet.DUE ->
            DueDialog(initial = task.due, onDismiss = onClose) {
                onEdit(Edit.SetField("due", it?.toString()))
                onClose()
            }
        Sheet.TAG ->
            InputSheet("Новый тег", "релиз", action = "Добавить", onDismiss = onClose) { tag ->
                onEdit(Edit.SetField("tags", tagsValue(task.tags + tag.trim().removePrefix("#"))))
                onClose()
            }
        Sheet.SUBTASK ->
            InputSheet("Подзадача", "что сделать", action = "Добавить", onDismiss = onClose) {
                onEdit(Edit.AddSubtask(it))
                onClose()
            }
        Sheet.DELETE ->
            DeleteTaskDialog(task.title, task.path, onDismiss = onClose) {
                onClose()
                onDelete()
            }
    }
}

/** Проект — из значений, что уже встречаются в задачах, плюс ввод своего (реестр — срез Н3). */
@Composable
private fun ProjectSheet(
    projects: List<String>,
    selected: String?,
    onClose: () -> Unit,
    onPick: (String?) -> Unit,
) {
    var typing by remember { mutableStateOf(false) }
    if (typing) {
        InputSheet("Новый проект", "tgsum", action = "Выбрать", onDismiss = onClose) {
            onPick(it.trim())
            onClose()
        }
    } else {
        ChoiceSheet(
            title = "Проект",
            choices =
                projects.map { Choice(it, it) } +
                    Choice(null, "Без проекта") +
                    Choice(NEW_PROJECT, "новый проект"),
            selected = selected,
            onDismiss = onClose,
        ) {
            if (it == NEW_PROJECT) {
                typing = true
            } else {
                onPick(it)
                onClose()
            }
        }
    }
}

@Composable
private fun Header(
    task: TaskFile.Task,
    editing: Boolean,
    onEditing: (Boolean) -> Unit,
    onTitle: (String) -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.padding(start = 10.dp, end = 10.dp, top = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = DocPalette.Mut)
            }
            IconButton(onClick = { onEditing(!editing) }) {
                Icon(Icons.Filled.Edit, "Править заголовок", tint = DocPalette.Mut)
            }
        }
        if (editing) {
            TitleField(task.title, onTitle) { onEditing(false) }
        } else {
            Text(
                task.title,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = TITLE_LINES,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 2.dp),
            )
        }
    }
}

/** Правка заголовка на месте: «Готово» и потеря фокуса сохраняют, Back отменяет без записи. */
@Composable
private fun TitleField(title: String, onTitle: (String) -> Unit, onClose: () -> Unit) {
    var draft by remember { mutableStateOf(title) }
    var cancelled by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    BackHandler {
        cancelled = true
        onClose()
    }
    val save = {
        if (!cancelled && draft.isNotBlank() && draft.trim() != title) onTitle(draft.trim())
        onClose()
    }
    TextField(
        value = draft,
        onValueChange = { draft = it },
        textStyle = MaterialTheme.typography.headlineSmall,
        singleLine = true,
        colors =
            TextFieldDefaults.colors(
                focusedContainerColor = DocPalette.Paper2,
                unfocusedContainerColor = DocPalette.Paper2,
            ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { save() }),
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = 12.dp)
                .imePadding()
                .focusRequester(focus)
                .onFocusChanged { if (!it.isFocused) save() },
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/** Сегмент статуса: `in_progress` ставится только здесь, чекбокс в списке бинарен. */
@Composable
private fun StatusSegment(status: String, onStatus: (String) -> Unit) {
    val items =
        listOf(
            TaskFile.STATUS_OPEN to "Открыта",
            TaskFile.STATUS_IN_PROGRESS to "В работе",
            TaskFile.STATUS_DONE to "Сделана",
        )
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 16.dp)
                .border(1.dp, DocPalette.Line, RoundedCornerShape(12.dp))
    ) {
        items.forEachIndexed { i, (value, label) ->
            if (i > 0) {
                Box(Modifier.width(1.dp).height(SEGMENT.dp).background(DocPalette.Line))
            }
            val on = value == status
            Box(
                modifier =
                    Modifier.weight(1f)
                        .clickable { onStatus(value) }
                        .background(if (on) BLUE_SOFT else DocPalette.Paper)
                        .height(SEGMENT.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style =
                        MaterialTheme.typography.bodySmall.copy(
                            color = if (on) DocPalette.Blue else DocPalette.Mut,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Fields(
    task: TaskFile.Task,
    today: LocalDate,
    onEdit: (Edit) -> Unit,
    onSheet: (Sheet) -> Unit,
) {
    Column(Modifier.padding(horizontal = 22.dp)) {
        FieldRow("Проект", task.project ?: "не выбран", muted = task.project == null) {
            onSheet(Sheet.PROJECT)
        }
        FieldRow("Приоритет", "${task.priority} · ${priorityWord(task.priority)}") {
            onSheet(Sheet.PRIORITY)
        }
        FieldRow(
            label = "Срок",
            value = task.due?.let { dueLabel(it, today) } ?: "без срока",
            muted = task.due == null,
            mono = task.due != null,
        ) {
            onSheet(Sheet.DUE)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FieldLabel("Теги")
            FlowRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                task.tags.forEach { tag ->
                    RemovableTag(tag) { onEdit(Edit.SetField("tags", tagsValue(task.tags - tag))) }
                }
                Text(
                    "+ тег",
                    style = MaterialTheme.typography.bodySmall.copy(color = DocPalette.Blue),
                    modifier =
                        Modifier.clickable { onSheet(Sheet.TAG) }
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                )
            }
        }
        HorizontalDivider(color = DocPalette.Line)
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(end = 10.dp).width(88.dp),
    )
}

@Composable
private fun FieldRow(
    label: String,
    value: String,
    muted: Boolean = false,
    mono: Boolean = false,
    onClick: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).height(TOUCH.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FieldLabel(label)
            Text(
                value,
                style =
                    if (mono) {
                        MaterialTheme.typography.labelMedium.copy(color = DocPalette.Ink)
                    } else {
                        MaterialTheme.typography.bodyMedium.copy(
                            color = if (muted) DocPalette.Mut else DocPalette.Ink,
                            fontWeight = if (muted) FontWeight.Normal else FontWeight.Medium,
                        )
                    },
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = DocPalette.Mut,
                modifier = Modifier.size(16.dp),
            )
        }
        HorizontalDivider(color = DocPalette.Line)
    }
}

@Composable
private fun Subtasks(task: TaskFile.Task, onEdit: (Edit) -> Unit, onAdd: () -> Unit) {
    Column(Modifier.padding(horizontal = 22.dp)) {
        val done = task.subtasks.count { it.done }
        Text(
            if (task.subtasks.isEmpty()) "ПОДЗАДАЧИ" else "ПОДЗАДАЧИ · $done/${task.subtasks.size}",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
        )
        task.subtasks.forEach { sub ->
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .clickable { onEdit(Edit.ToggleSubtask(sub.text, !sub.done)) }
                        .height(TOUCH.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SubCheckbox(sub.done)
                Text(
                    sub.text,
                    style =
                        if (sub.done) {
                            MaterialTheme.typography.bodyMedium.copy(
                                color = DocPalette.Mut,
                                textDecoration = TextDecoration.LineThrough,
                            )
                        } else {
                            MaterialTheme.typography.bodyMedium
                        },
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onAdd).height(TOUCH.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(17.dp).border(1.5.dp, DocPalette.Blue, RoundedCornerShape(5.dp)))
            Text(
                "Добавить подзадачу",
                style = MaterialTheme.typography.bodyMedium.copy(color = DocPalette.Blue),
            )
        }
    }
}

@Composable
private fun SubCheckbox(done: Boolean) {
    Box(
        modifier =
            Modifier.size(17.dp)
                .then(
                    if (done) {
                        Modifier.background(DocPalette.Nav, RoundedCornerShape(5.dp))
                    } else {
                        Modifier.border(1.5.dp, DocPalette.Mut, RoundedCornerShape(5.dp))
                    }
                ),
        contentAlignment = Alignment.Center,
    ) {
        if (done) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = DocPalette.OnNav,
                modifier = Modifier.size(11.dp),
            )
        }
    }
}

/**
 * ponytail: ссылка на источник ведёт в GitHub — открытие заметки внутри приложения требует
 * тождества «путь в репо ↔ локальная запись» (NoteRef), оно приходит со срезом ленты Н5.
 */
@Composable
private fun Source(source: String, onOpen: (String) -> Unit) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clickable { onOpen(source) }
                .padding(horizontal = 22.dp)
                .height(TOUCH.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ExitToApp,
            contentDescription = null,
            tint = DocPalette.Blue,
            modifier = Modifier.size(15.dp),
        )
        Text(
            "из заметки: ${source.substringAfterLast('/').removeSuffix(".md")}",
            style = MaterialTheme.typography.bodySmall.copy(color = DocPalette.Blue),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Три состояния записи: оптимистично видно сразу, янтарь — в очереди, зелень — ушло коммитом. */
@Composable
private fun StatusLine(path: String, pending: Boolean) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 6.dp)
                .background(DocPalette.Paper2, RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            if (pending) Icons.Filled.Edit else Icons.Filled.Check,
            contentDescription = null,
            tint = if (pending) DocPalette.Amber else DocPalette.Green,
            modifier = Modifier.size(14.dp),
        )
        Text(
            if (pending) {
                "$path · в очереди — уйдёт при сети"
            } else {
                "$path · правки уходят коммитом"
            },
            style =
                MaterialTheme.typography.bodySmall.copy(
                    color = if (pending) DocPalette.Amber else DocPalette.Mut
                ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Actions(onDelete: () -> Unit, onGithub: () -> Unit) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 14.dp)
                .navigationBarsPadding(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ActionButton("Удалить", Icons.Filled.Delete, DocPalette.Err, Modifier.weight(1f), onDelete)
        ActionButton(
            "Открыть в GitHub",
            Icons.AutoMirrored.Filled.ExitToApp,
            DocPalette.Ink,
            Modifier.weight(1f),
            onGithub,
        )
    }
}

@Composable
private fun ActionButton(
    label: String,
    icon: ImageVector,
    color: Color,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            modifier
                .border(
                    1.dp,
                    if (color == DocPalette.Err) ERR_BORDER else DocPalette.Line,
                    RoundedCornerShape(12.dp),
                )
                .clickable(onClick = onClick)
                .height(TOUCH.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(15.dp))
        Box(Modifier.size(width = 8.dp, height = 1.dp))
        Text(label, style = MaterialTheme.typography.bodySmall.copy(color = color))
    }
}

/** Крестик у тега рисуется в самом чипе — отдельного режима правки нет. */
@Composable
private fun RemovableTag(tag: String, onRemove: () -> Unit) {
    Row(
        modifier =
            Modifier.background(DocPalette.Paper2, RoundedCornerShape(8.dp))
                .clickable(onClick = onRemove)
                .padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("#$tag", style = MaterialTheme.typography.bodySmall.copy(color = DocPalette.Ink))
        Icon(
            Icons.Filled.Close,
            contentDescription = "убрать тег",
            tint = DocPalette.Mut,
            modifier = Modifier.size(12.dp).padding(start = 2.dp),
        )
    }
}

/** Теги во frontmatter — инлайн-список; пустой список означает «ключ убрать». */
internal fun tagsValue(tags: List<String>): String? =
    tags
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .takeIf { it.isNotEmpty() }
        ?.let { "[${it.joinToString(", ")}]" }

private const val TOUCH = 48
private const val SEGMENT = 44
private const val TITLE_LINES = 3
private const val NEW_PROJECT = " новый"
private val BLUE_SOFT = Color(0x1A3A6FB8)
private val ERR_BORDER = Color(0x33B3261E)
