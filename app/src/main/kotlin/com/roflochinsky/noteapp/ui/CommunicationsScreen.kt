package com.roflochinsky.noteapp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.roflochinsky.noteapp.pipeline.Communication
import com.roflochinsky.noteapp.pipeline.CommunicationStore
import com.roflochinsky.noteapp.pipeline.draftSize

@Composable
fun CommunicationsScreen(
    sources: List<Communication>,
    projects: List<String>,
    incoming: Communication?,
    error: String?,
    busy: Boolean,
    onBack: () -> Unit,
    onImport: () -> Unit,
    onIncomingConsumed: () -> Unit,
    onSave: (Communication, () -> Unit) -> Unit,
    onExport: (String?, Boolean) -> Unit,
    onTasks: () -> Unit = {},
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var draft by
        rememberSaveable(stateSaver = CommunicationSaver) { mutableStateOf(Communication()) }
    var project by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var confirmExport by remember { mutableStateOf<Boolean?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var replaceIncoming by remember { mutableStateOf(false) }
    var inputError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(incoming?.id) {
        incoming?.let {
            if (editing) replaceIncoming = true
            else {
                draft = it
                editing = true
                onIncomingConsumed()
            }
        }
    }
    val back = { if (editing) confirmDiscard = true else onBack() }
    BackHandler { if (!busy) back() }
    Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            TextButton(onClick = back, enabled = !busy) { Text("Назад") }
            Text(
                "Коммуникации",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        if (error != null)
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
            )
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (editing) {
            inputError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            CommunicationEditor(
                draft,
                {
                    if (
                        !busy &&
                            it.draftSize() <= CommunicationStore.MAX_DRAFT &&
                            it.text.length <= CommunicationStore.MAX_TEXT
                    ) {
                        draft = it
                        inputError = null
                    } else
                        inputError =
                            "Исходный текст: до 100 000 символов, всё вместе: до 120 000. " +
                                "Вставка отклонена; разделите источник."
                },
                busy,
                onSave = {
                    onSave(draft) {
                        editing = false
                        draft = Communication()
                    }
                },
            )
        } else {
            Text(
                "Тексты хранятся только на этом устройстве. Сохраните выгрузку, чтобы иметь копию.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                TextButton(
                    onClick = {
                        draft = Communication()
                        editing = true
                    },
                    enabled = !busy,
                ) {
                    Text("Добавить текст")
                }
                TextButton(onClick = onImport, enabled = !busy) { Text("Импорт .txt / .md") }
            }
            TextButton(onClick = onTasks, modifier = Modifier.padding(horizontal = 12.dp)) {
                Text("Открыть задачи")
            }
            ProjectChoice(
                project,
                projects + sources.map { it.project }.filter { it.isNotBlank() },
            ) {
                project = it
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                TextButton(onClick = { confirmExport = false }, enabled = !busy) {
                    Text("Сохранить .md")
                }
                TextButton(onClick = { confirmExport = true }, enabled = !busy) {
                    Text("Передать контекст")
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it.take(500) },
                label = { Text("Поиск по текстам") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 4.dp),
            )
            LazyColumn(Modifier.weight(1f).padding(horizontal = 22.dp)) {
                val visible =
                    sources.filter {
                        (project == null || it.project == project) &&
                            (it.title + " " + it.text + " " + it.project + " " + it.source)
                                .contains(query, ignoreCase = true)
                    }
                if (visible.isEmpty())
                    item {
                        Text(
                            "Нет локальных текстов. Добавьте переписку, TGSUM-сводку или расшифровку " +
                                "звонка.\n\nВыгрузка также включает доступные заметки и задачи выбранного проекта.",
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                items(visible, key = { it.id }) { source ->
                    Column(
                        Modifier.fillMaxWidth()
                            .clickable {
                                draft = source
                                editing = true
                            }
                            .padding(vertical = 14.dp)
                    ) {
                        Text(source.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            listOf(source.source, source.project, source.date)
                                .filter { it.isNotBlank() }
                                .joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = DocPalette.Mut,
                        )
                        Text(
                            "Только на устройстве",
                            style = MaterialTheme.typography.labelSmall,
                            color = DocPalette.Amber,
                        )
                        Text(
                            source.text.take(160),
                            maxLines = 3,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    HorizontalDivider(color = DocPalette.Line)
                }
            }
        }
    }
    if (replaceIncoming)
        AlertDialog(
            onDismissRequest = {
                replaceIncoming = false
                onIncomingConsumed()
            },
            title = { Text("Получен новый текст") },
            text = { Text("Заменить несохранённый черновик новым текстом?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        incoming?.let {
                            draft = it
                            editing = true
                        }
                        replaceIncoming = false
                        onIncomingConsumed()
                    }
                ) {
                    Text("Заменить черновик")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        replaceIncoming = false
                        onIncomingConsumed()
                    }
                ) {
                    Text("Оставить текущий")
                }
            },
        )
    if (confirmDiscard)
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Закрыть без сохранения?") },
            text = {
                Text("Изменения в этом тексте не сохранятся. Уже сохранённый источник останется.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDiscard = false
                        editing = false
                        draft = Communication()
                    }
                ) {
                    Text("Не сохранять")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("Продолжить правку") }
            },
        )
    confirmExport?.let { share ->
        AlertDialog(
            onDismissRequest = { confirmExport = null },
            title = {
                Text(if (share) "Передать выбранный контекст?" else "Сохранить копию контекста?")
            },
            text = {
                Text(
                    "Проект: ${project ?: "Все проекты"}. " +
                        "Выгрузка включает полные тексты, заметки и задачи из локального снимка. " +
                        "Проверьте получателя и место сохранения. Аудио и ключи не включаются; " +
                        "jey получит файл только если вы сами его отправите."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmExport = null
                        onExport(project, share)
                    }
                ) {
                    Text("Продолжить")
                }
            },
            dismissButton = { TextButton(onClick = { confirmExport = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun ProjectChoice(project: String?, projects: List<String>, onProject: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.padding(horizontal = 12.dp)) {
        TextButton(onClick = { expanded = true }) { Text("Проект: ${project ?: "Все проекты"} ▾") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Все проекты") },
                onClick = {
                    onProject(null)
                    expanded = false
                },
            )
            projects.distinct().sorted().forEach { name ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        onProject(name)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun CommunicationEditor(
    value: Communication,
    onChange: (Communication) -> Unit,
    busy: Boolean,
    onSave: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)
    ) {
        Text(
            "Сохранение только на устройстве. Исходный текст не отправляется в STT или GitHub.",
            style = MaterialTheme.typography.bodySmall,
        )
        EditField("Заголовок", value.title, enabled = !busy) { onChange(value.copy(title = it)) }
        SourceChoice(value.source, enabled = !busy) { onChange(value.copy(source = it)) }
        EditField("Проект (необязательно)", value.project, enabled = !busy) {
            onChange(value.copy(project = it))
        }
        EditField("Дата коммуникации: ГГГГ-ММ-ДД", value.date, enabled = !busy) {
            onChange(value.copy(date = it))
        }
        EditField("Ссылка на источник (необязательно)", value.url, enabled = !busy) {
            onChange(value.copy(url = it))
        }
        EditField("Исходный текст", value.text, minLines = 6, enabled = !busy) {
            onChange(value.copy(text = it))
        }
        Text(
            "Дополнения вручную: оставьте неизвестное пустым. Эти поля не изменяют исходный текст.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 16.dp),
        )
        EditField("Решения", value.decisions, enabled = !busy) {
            onChange(value.copy(decisions = it))
        }
        EditField("Обещания / договорённости", value.commitments, enabled = !busy) {
            onChange(value.copy(commitments = it))
        }
        EditField("Ответственные", value.owners, enabled = !busy) {
            onChange(value.copy(owners = it))
        }
        EditField("Сроки", value.deadlines, enabled = !busy) {
            onChange(value.copy(deadlines = it))
        }
        EditField("Блокеры", value.blockers, enabled = !busy) {
            onChange(value.copy(blockers = it))
        }
        Button(
            onClick = onSave,
            enabled = !busy && value.title.isNotBlank() && value.text.isNotBlank(),
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp).heightIn(min = 52.dp),
        ) {
            Text("Сохранить на устройстве")
        }
    }
}

@Composable
private fun EditField(
    label: String,
    value: String,
    minLines: Int = 1,
    enabled: Boolean = true,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        label = { Text(label) },
        minLines = minLines,
        maxLines = if (minLines > 1) 12 else 4,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
    )
}

@Composable
private fun SourceChoice(source: String, enabled: Boolean, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, enabled = enabled) { Text("Источник: $source ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(
                    "Telegram / TGSUM",
                    "Разговор на площадке",
                    "Почта",
                    "Звонок / транскрипт",
                    "Другое",
                )
                .forEach { name ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            onChange(name)
                            open = false
                        },
                    )
                }
        }
    }
}

private val CommunicationSaver =
    listSaver<Communication, String>(
        save = {
            listOf(
                it.id,
                it.title,
                it.source,
                it.project,
                it.date,
                it.url,
                it.text,
                it.decisions,
                it.commitments,
                it.owners,
                it.deadlines,
                it.blockers,
            )
        },
        restore = {
            Communication(
                it[0],
                it[1],
                it[2],
                it[3],
                it[4],
                it[5],
                it[6],
                it[7],
                it[8],
                it[9],
                it[10],
                it[11],
            )
        },
    )
