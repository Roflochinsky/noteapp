package com.roflochinsky.noteapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.roflochinsky.noteapp.pipeline.TaskFile
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Шторки задач по компу v2: выбор значения строками `.opt` с радио, ввод одной строкой, срок —
 * системным датапикером, шторка «Новая задача» (борд 4). Один набор на деталку и на создание —
 * выбор поля выглядит одинаково в обоих местах.
 */
data class Choice(val value: String?, val label: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Sheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = DocPalette.Paper,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(Modifier.imePadding().padding(26.dp, 0.dp, 26.dp, 28.dp)) { content() }
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

/** Строки выбора: радио, подпись, счётчик справа (нулевые значения остаются со счётчиком 0). */
@Composable
private fun ChoiceRows(
    choices: List<Choice>,
    selected: String?,
    counts: Map<String?, Int>,
    onPick: (String?) -> Unit,
) {
    choices.forEachIndexed { i, choice ->
        if (i > 0) HorizontalDivider(color = DocPalette.Line)
        Row(
            modifier = Modifier.fillMaxWidth().clickable { onPick(choice.value) }.height(TOUCH.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Radio(choice.value == selected)
            Text(
                choice.label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            counts[choice.value]?.let {
                Text(
                    it.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

@Composable
fun ChoiceSheet(
    title: String,
    choices: List<Choice>,
    selected: String?,
    counts: Map<String?, Int> = emptyMap(),
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit,
) {
    Sheet(onDismiss) {
        SheetTitle(title)
        ChoiceRows(choices, selected, counts, onPick)
    }
}

@Composable
private fun Radio(on: Boolean) {
    Box(
        Modifier.size(19.dp)
            .then(
                if (on) {
                    Modifier.border(5.5.dp, DocPalette.Blue, CircleShape)
                } else {
                    Modifier.border(1.5.dp, DocPalette.Mut, CircleShape)
                }
            )
    )
}

/** Поле ввода одной строкой: тег, подзадача, новый проект, заголовок новой задачи. */
@Composable
private fun InputBox(
    value: String,
    placeholder: String,
    onValue: (String) -> Unit,
    onDone: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        placeholder = { Text(placeholder, style = MaterialTheme.typography.bodyMedium) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        colors =
            TextFieldDefaults.colors(
                focusedContainerColor = DocPalette.Paper,
                unfocusedContainerColor = DocPalette.Paper,
                focusedIndicatorColor = DocPalette.Blue,
                unfocusedIndicatorColor = DocPalette.Line,
            ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}

@Composable
private fun Cta(label: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(54.dp).padding(top = 4.dp),
        shape = RoundedCornerShape(14.dp),
        colors =
            ButtonDefaults.buttonColors(
                containerColor = DocPalette.Nav,
                contentColor = DocPalette.OnNav,
                disabledContainerColor = DocPalette.Paper2,
                disabledContentColor = DocPalette.Mut,
            ),
    ) {
        Text(label, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun InputSheet(
    title: String,
    placeholder: String,
    action: String = "Готово",
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    Sheet(onDismiss) {
        SheetTitle(title)
        InputBox(text, placeholder, { text = it }) { if (text.isNotBlank()) onSave(text) }
        Box(Modifier.height(12.dp))
        Cta(action, text.isNotBlank()) { onSave(text) }
    }
}

/** Срок — системный датапикер: своего календаря в мире «Документ» нет и не надо. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DueDialog(initial: LocalDate?, onDismiss: () -> Unit, onPick: (LocalDate?) -> Unit) {
    val state =
        rememberDatePickerState(
            initialSelectedDateMillis = initial?.let { it.toEpochDay() * DAY_MS }
        )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        colors = DatePickerDefaults.colors(containerColor = DocPalette.Paper),
        confirmButton = {
            TextButton(
                onClick = {
                    onPick(
                        state.selectedDateMillis?.let {
                            Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                        }
                    )
                }
            ) {
                Text("Готово", color = DocPalette.Blue)
            }
        },
        dismissButton = {
            TextButton(onClick = { onPick(null) }) { Text("Без срока", color = DocPalette.Mut) }
        },
    ) {
        DatePicker(state = state, title = null)
    }
}

/**
 * Диалог удаления: заголовок, название, путь файла, два действия. Чекбоксов нет намеренно — три
 * галочки спеки (файл в репо / аудио / порождённые задачи) относятся к заметке, а у задачи
 * применима ровно одна, всегда отмеченная. Решение записано пунктом 27 плана
 * `docs/plans/2026-08-26-tasks-v2.md` (находка Д12); галочки появятся в срезе Н6, где целей
 * действительно несколько.
 */
@Composable
fun DeleteTaskDialog(title: String, path: String, onDismiss: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DocPalette.Paper,
        shape = RoundedCornerShape(24.dp),
        title = { Text("Удалить задачу?", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(path, style = MaterialTheme.typography.labelMedium)
                Text(
                    "Файл исчезнет из репозитория. Вернуть его можно только через историю git.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDelete) { Text("Удалить", color = DocPalette.Err) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена", color = DocPalette.Blue) }
        },
    )
}

/** Что владелец задаёт при создании: заголовок обязателен, остальное — чипами (борд 4). */
data class NewTask(
    val title: String = "",
    val project: String? = null,
    val priority: String = TaskFile.PRIORITY_DEFAULT,
    val due: LocalDate? = null,
    val tags: List<String> = emptyList(),
)

private enum class Picking {
    PROJECT,
    PRIORITY,
    TAG,
}

/**
 * Шторка «Новая задача» (борд 4): ввод, чипы значений, кнопка и путь будущего файла. Выбор значения
 * раскрывается в этой же шторке — вложенных шторок в Compose не бывает.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewTaskSheet(
    projects: List<String>,
    today: LocalDate,
    taken: Set<String>,
    onDismiss: () -> Unit,
    onCreate: (NewTask) -> Unit,
) {
    var draft by remember { mutableStateOf(NewTask()) }
    var picking by remember { mutableStateOf<Picking?>(null) }
    var dueOpen by remember { mutableStateOf(false) }
    Sheet(onDismiss) {
        when (picking) {
            Picking.PROJECT ->
                ChoiceRows(
                    choices = projects.map { Choice(it, it) } + Choice(null, "Без проекта"),
                    selected = draft.project,
                    counts = emptyMap(),
                ) {
                    draft = draft.copy(project = it)
                    picking = null
                }
            Picking.PRIORITY ->
                ChoiceRows(
                    choices = TaskFilter.PRIORITIES.map { Choice(it, "$it · ${priorityWord(it)}") },
                    selected = draft.priority,
                    counts = emptyMap(),
                ) {
                    draft = draft.copy(priority = it ?: TaskFile.PRIORITY_DEFAULT)
                    picking = null
                }
            Picking.TAG -> {
                var tag by remember { mutableStateOf("") }
                val add = {
                    if (tag.isNotBlank()) draft = draft.copy(tags = draft.tags + tag.trim())
                    picking = null
                }
                SheetTitle("Тег")
                InputBox(tag, "релиз", { tag = it.removePrefix("#") }) { add() }
                Box(Modifier.height(12.dp))
                Cta("Добавить", tag.isNotBlank()) { add() }
            }
            null -> {
                Text(
                    "Новая задача",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                InputBox(draft.title, "что сделать", { draft = draft.copy(title = it) }) {
                    if (draft.title.isNotBlank()) onCreate(draft)
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    // Комп: `.chips{gap:8px; flex-wrap:wrap}` — 8px и между рядами тоже.
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Chip(draft.project ?: "Без проекта", draft.project != null, true) {
                        picking = Picking.PROJECT
                    }
                    Chip(draft.priority, true) { picking = Picking.PRIORITY }
                    Chip(
                        draft.due?.let { dueLabel(it, today) } ?: "Срок",
                        draft.due != null,
                        true,
                    ) {
                        dueOpen = true
                    }
                    draft.tags.forEach { tag ->
                        Chip("#$tag", true) { draft = draft.copy(tags = draft.tags - tag) }
                    }
                    Chip("#тег", false) { picking = Picking.TAG }
                }
                Box(Modifier.height(16.dp))
                Cta("Создать задачу", draft.title.isNotBlank()) { onCreate(draft) }
                Text(
                    if (draft.title.isBlank()) {
                        "файл появится в tasks/ · один коммит"
                    } else {
                        "${TaskFile.DIR}${TaskFile.fileName(today, draft.title, taken)} · " +
                            "один коммит"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
            }
        }
    }
    if (dueOpen) {
        DueDialog(draft.due, onDismiss = { dueOpen = false }) {
            draft = draft.copy(due = it)
            dueOpen = false
        }
    }
}

/**
 * Чип по компу: рамка, активный — синий на подложке; шеврон у тех, что открывают выбор. Высоту ряда
 * задаёт сам чип (`.chip{padding:7px 12px}` ≈ 31dp) — растягивать его до 48dp нельзя: ряд шторки
 * создания состоит из одних чипов и вырастал весь, а при переносе — вдвое. Недостающие 8,5dp сверху
 * и снизу добирает near-hit Compose (см. [TOUCH]); заодно тап в зазор `spacedBy` достаётся
 * БЛИЖАЙШЕМУ чипу, а не тому, что нарисован позже.
 */
@Composable
private fun Chip(label: String, on: Boolean, chevron: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier =
            Modifier.clickable(onClick = onClick)
                .border(
                    1.dp,
                    if (on) DocPalette.Blue else DocPalette.Line,
                    RoundedCornerShape(9.dp),
                )
                .background(
                    if (on) DocPalette.BlueSoft else DocPalette.Paper,
                    RoundedCornerShape(9.dp),
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            label,
            style =
                MaterialTheme.typography.bodySmall.copy(
                    color = if (on) DocPalette.Blue else DocPalette.Mut,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                ),
        )
        if (chevron) {
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = if (on) DocPalette.Blue else DocPalette.Mut,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

private const val DAY_MS = 86_400_000L
