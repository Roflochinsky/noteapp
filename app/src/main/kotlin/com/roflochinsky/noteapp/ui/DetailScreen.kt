package com.roflochinsky.noteapp.ui

import android.media.MediaPlayer
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roflochinsky.noteapp.pipeline.Edit
import com.roflochinsky.noteapp.pipeline.FeedItem
import com.roflochinsky.noteapp.pipeline.Frontmatter
import com.roflochinsky.noteapp.pipeline.NoteFile
import com.roflochinsky.noteapp.pipeline.NoteRef
import com.roflochinsky.noteapp.pipeline.NotesStore
import com.roflochinsky.noteapp.pipeline.TaskFile
import com.roflochinsky.noteapp.pipeline.TranscriptMapper
import java.io.File

/**
 * Деталка заметки v2 по компу (борд 6): поля-чипы, вкладки Саммари / Транскрипт / Аудио, задачи
 * ссылками, статус-строка с путём файла.
 *
 * **Долг Н1 закрыт здесь.** До этого среза экран сам собирал `GithubClient`, звал `findDonePath` и
 * читал файл из сети — то есть UI знал про HTTP. Теперь заметка приезжает готовой строкой ленты
 * ([FeedItem], склейка `NotesStore ∪ RepoCache` по `NoteRef`), а правка уходит той же очередью, что
 * и правки задач: экран не знает ни про кэш, ни про GitHub (решение LLD-24).
 *
 * Транскрипт **не редактируется**: доверие к расшифровке — отдельный эпик (`nikitatrubaev-7cy`).
 */
@Composable
fun DetailScreen(
    item: FeedItem,
    people: List<String>,
    projects: List<String>,
    tasks: List<TaskFile.Task>,
    pending: Boolean,
    onEdit: (Edit) -> Unit,
    onOpen: (String) -> Unit,
    onTask: (String) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val dir = remember(item.noteId) { item.noteId?.let { NotesStore.noteDir(context, it) } }
    // Транскрипт с телефона, а для заметки, которой на этом телефоне нет, — из файла репо.
    val transcript =
        remember(item.ref) {
            dir?.let { File(it, NotesStore.TRANSCRIPT_MD).takeIf(File::exists)?.readText() }
                ?: item.note?.section(TRANSCRIPT)?.trim().orEmpty()
        }
    val marks =
        remember(item.ref) {
            dir?.let { File(it, NotesStore.MARKS).takeIf(File::exists) }
                ?.readLines()
                ?.mapNotNull { it.trim().toLongOrNull() }
                .orEmpty()
        }
    var tab by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        Head(item.title.ifEmpty { "Заметка" }, onBack)
        // Заметку из `inbox/` не правим: её прямо сейчас обрабатывает Action (решение LLD-7).
        item.note
            ?.takeIf { NoteRef.isEditable(it.path) }
            ?.let { MetaChips(it, people, projects, onEdit) }
        TabRow(
            selectedTabIndex = tab,
            containerColor = DocPalette.Paper,
            contentColor = DocPalette.Blue,
            indicator = { pos ->
                TabRowDefaults.SecondaryIndicator(
                    Modifier.tabIndicatorOffset(pos[tab]),
                    color = DocPalette.Blue,
                )
            },
        ) {
            listOf("Саммари", "Транскрипт", "Аудио").forEachIndexed { i, t ->
                Tab(
                    selected = tab == i,
                    onClick = { tab = i },
                    text = {
                        Text(
                            t,
                            color = if (tab == i) DocPalette.Blue else DocPalette.Mut,
                            maxLines = 1,
                            softWrap = false,
                            style =
                                MaterialTheme.typography.bodySmall.copy(color = Color.Unspecified),
                        )
                    },
                )
            }
        }
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> SummaryTab(item, tasks.filter { it.source == item.path }, onTask)
                1 -> TranscriptTab(transcript)
                else -> AudioTab(dir?.let { File(it, NotesStore.AUDIO) }, marks)
            }
        }
        StatusLine(item, pending, onRetry)
        item.path?.let { Actions { onOpen(it) } }
    }
}

@Composable
private fun Head(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = DocPalette.Mut)
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp, end = 12.dp),
        )
    }
}

/**
 * Поля-чипы компа: тип и проект — выбор шторкой, участники — набор из реестра, теги — пассивные
 * чипы без действия (в компе у `#релиз` нет ни шеврона, ни плюса).
 *
 * Правка поля файл не переименовывает и не переносит между папками типов (решение LLD-20): путь
 * заметки неизменен, иначе рвутся `source` у задач, кэш и очередь.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MetaChips(
    note: NoteFile.Note,
    people: List<String>,
    projects: List<String>,
    onEdit: (Edit) -> Unit,
) {
    var open by remember { mutableStateOf<String?>(null) }
    FlowRow(
        modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MetaChip(note.type ?: "тип", chevron = true) { open = "type" }
        MetaChip(note.project ?: "проект", chevron = true) { open = "project" }
        MetaChip(
            note.participants.joinToString(", ").ifEmpty { "участники" },
            plus = true,
            onClick = { open = "participants" },
        )
        note.tags.forEach { MetaChip("#$it") }
    }
    when (open) {
        "type" ->
            ChoiceSheet(
                title = "Тип",
                choices = NoteFilter.TYPES.map { Choice(it, it) },
                selected = note.type,
                onDismiss = { open = null },
            ) { picked ->
                picked?.let { onEdit(Edit.SetField("type", it)) }
                open = null
            }
        "project" ->
            ChoiceSheet(
                title = "Проект",
                choices =
                    listOf(Choice(null, "Без проекта")) +
                        (projects + listOfNotNull(note.project)).distinct().map { Choice(it, it) },
                selected = note.project,
                onDismiss = { open = null },
            ) { picked ->
                onEdit(Edit.SetField("project", picked))
                open = null
            }
        "participants" ->
            PeopleSheet(note.participants, people, onDismiss = { open = null }) { picked ->
                onEdit(Edit.SetField("participants", Frontmatter.inline(picked)))
            }
    }
}

/**
 * Чип поля: контур 1px `line`, радиус 9dp, паддинг 7×12 (DESIGN.md, Chips). Пассивный — без иконки
 * и без клика, как тег в компе.
 *
 * ponytail: четвёртая копия чипа в пакете (`FilterChips`, `TaskSheets`, `NoteChips`) — цена
 * параллельных срезов, свести их все — `bd nikitatrubaev-0rk.29`.
 */
@Composable
private fun MetaChip(
    label: String,
    chevron: Boolean = false,
    plus: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier =
            Modifier.border(1.dp, DocPalette.Line, RoundedCornerShape(9.dp))
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        if (chevron || plus) {
            Icon(
                if (plus) Icons.Filled.Add else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = DocPalette.Mut,
                modifier = Modifier.size(CHEVRON.dp),
            )
        }
    }
}

/**
 * Участники: сперва те, кого Claude услышал в записи и кого нет в реестре, потом сам реестр
 * `people.md` — так шторка нарисована в компе (борд 7).
 *
 * Карандаш переименования рисуется, но выключен: переименование персоны по всем заметкам одним
 * коммитом — срез Н8, а кнопка, которая молча ничего не делает, хуже выключенной (вердикт UX).
 * Строки «найти или добавить персону» здесь нет по той же причине — пополнение реестра `bd
 * nikitatrubaev-0rk.23`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeopleSheet(
    picked: List<String>,
    people: List<String>,
    onDismiss: () -> Unit,
    onPick: (List<String>) -> Unit,
) {
    val heard = picked.filterNot { it in people }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = DocPalette.Paper,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(Modifier.padding(26.dp, 0.dp, 26.dp, 28.dp)) {
            Text("Участники", style = OverlayHeadline, modifier = Modifier.padding(bottom = 8.dp))
            if (heard.isNotEmpty()) {
                Rubric("Claude услышал в записи")
                heard.forEach { PersonRow(it, true) { onPick(picked - it) } }
            }
            Rubric("Реестр · people.md")
            people.forEach { person ->
                val on = person in picked
                PersonRow(person, on) { onPick(if (on) picked - person else picked + person) }
            }
        }
    }
}

@Composable
private fun Rubric(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
    )
}

@Composable
private fun PersonRow(person: String, on: Boolean, onToggle: () -> Unit) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .toggleable(value = on, onValueChange = { onToggle() })
                .heightIn(min = TOUCH.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier =
                Modifier.size(CHECKBOX.dp)
                    .background(
                        if (on) DocPalette.Nav else Color.Transparent,
                        RoundedCornerShape(6.dp),
                    )
                    .border(
                        1.5.dp,
                        if (on) DocPalette.Nav else DocPalette.Mut,
                        RoundedCornerShape(6.dp),
                    ),
            contentAlignment = Alignment.Center,
        ) {
            if (on) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = DocPalette.OnNav,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
        Text(person, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = {}, enabled = false) {
            Icon(Icons.Filled.Create, "Переименовать $person", tint = DocPalette.Mut)
        }
    }
}

/**
 * Саммари как документ: рубрика — жирная строка `**Суть.**`, дальше абзацы и пункты. Секция
 * «Задачи» из текста не рисуется — вместо неё живые ссылки на файлы задач: статусы живут в задачах,
 * и второй их источник в заметке разошёлся бы с первым (комп, борд 6).
 */
@Composable
private fun SummaryTab(item: FeedItem, tasks: List<TaskFile.Task>, onTask: (String) -> Unit) {
    val summary = item.note?.section(NoteFile.SUMMARY)?.trim()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp)) {
        if (summary.isNullOrEmpty()) {
            Text(
                if (item.note == null) "саммари готовится…" else "саммари ещё не дописано",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            summary.lines().takeWhile { !it.startsWith(TASKS_BLOCK) }.forEach { Line(it) }
        }
        if (tasks.isNotEmpty()) {
            Text(
                "ЗАДАЧИ · В ТРЕКЕРЕ",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
            tasks.forEach { task -> TaskLink(task, onTask) }
            Text(
                "Статусы живут в задачах — здесь только ссылки.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun Line(line: String) {
    when {
        line.startsWith("**") -> {
            val label = line.removePrefix("**").substringBefore("**")
            Text(
                label.uppercase().trimEnd('.'),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
            val rest = line.removePrefix("**").substringAfter("**").trim()
            if (rest.isNotEmpty()) Text(rest, style = MaterialTheme.typography.bodyMedium)
        }
        line.isNotBlank() ->
            Text(
                line,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 2.dp),
            )
    }
}

@Composable
private fun TaskLink(task: TaskFile.Task, onTask: (String) -> Unit) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clickable { onTask(task.path) }
                .heightIn(min = TOUCH.dp)
                .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(6.dp).background(DocPalette.Blue, CircleShape))
        Text(
            task.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(task.priority, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun TranscriptTab(transcript: String) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp, 14.dp)) {
        if (transcript.isBlank()) {
            Text("расшифровка ещё не готова", style = MaterialTheme.typography.bodySmall)
        }
        transcript
            .lines()
            .filter { it.isNotBlank() }
            .forEach { line ->
                val time = line.substringAfter('[', "").substringBefore(']', "")
                val speaker = line.substringAfter("] ", "").substringBefore(':', "")
                val text = line.substringAfter(": ", line)
                Row(Modifier.padding(bottom = 12.dp)) {
                    Column(Modifier.width(72.dp)) {
                        Text(
                            speaker,
                            style =
                                MaterialTheme.typography.labelMedium.copy(color = DocPalette.Blue),
                        )
                        Text(time, style = MaterialTheme.typography.labelMedium)
                    }
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
    }
}

@Composable
private fun AudioTab(audio: File?, marks: List<Long>) {
    val player = remember { MediaPlayer() }
    var playing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    DisposableEffect(Unit) { onDispose { player.release() } }
    LaunchedEffect(playing) {
        while (playing) {
            progress =
                if (player.duration > 0) player.currentPosition.toFloat() / player.duration else 0f
            kotlinx.coroutines.delay(POLL_MS)
            if (!player.isPlaying) playing = false
        }
    }
    Column(Modifier.padding(22.dp)) {
        if (audio == null || !audio.exists()) {
            Text(
                "аудио осталось на том телефоне, где сделана запись",
                style = MaterialTheme.typography.bodySmall,
            )
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = {
                    if (playing) {
                        player.pause()
                        playing = false
                    } else {
                        runCatching {
                            player.reset()
                            player.setDataSource(audio.absolutePath)
                            player.prepare()
                            player.start()
                            playing = true
                        }
                    }
                },
                modifier = Modifier.size(52.dp).background(DocPalette.Nav, CircleShape),
            ) {
                Icon(Icons.Filled.PlayArrow, "Слушать", tint = DocPalette.Paper)
            }
            Spacer(Modifier.width(14.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.weight(1f).height(4.dp),
                color = DocPalette.Blue,
                trackColor = DocPalette.Line,
            )
        }
        if (marks.isNotEmpty()) {
            Text(
                "МОМЕНТЫ",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 22.dp, bottom = 6.dp),
            )
            marks.forEach {
                Text(
                    "⚑ ${TranscriptMapper.timecode(it)}",
                    style = MaterialTheme.typography.labelMedium.copy(color = DocPalette.Blue),
                    modifier =
                        Modifier.clickable {
                                runCatching {
                                    player.seekTo(it.toInt())
                                    if (!player.isPlaying) {
                                        player.start()
                                        playing = true
                                    }
                                }
                            }
                            .padding(vertical = 4.dp),
                )
            }
        }
        Text(
            "Аудио хранится только на телефоне и в GitHub не публикуется.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 18.dp),
        )
    }
}

/**
 * Втопленная плашка компа (`statusline`, `paper2`, радиус 10): моно-путь файла и зелёная галочка.
 *
 * Три состояния (решение владельца 2026-08-26 (б)): доставлено — путь и напоминание, что транскрипт
 * не редактируется; правка в очереди — янтарь; запись ещё не уехала — янтарное «не отправлено ·
 * Повторить», куда переехала кнопка «Повторить отправку» (а «Поделиться» из деталки ушло).
 */
@Composable
private fun StatusLine(item: FeedItem, pending: Boolean, onRetry: () -> Unit) {
    val path = item.path
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = 22.dp)
                .background(DocPalette.Paper2, RoundedCornerShape(10.dp))
                .then(if (path == null) Modifier.clickable(onClick = onRetry) else Modifier)
                .heightIn(min = TOUCH.dp)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            path == null ->
                Text(
                    "не отправлено · Повторить",
                    style = MaterialTheme.typography.bodySmall.copy(color = DocPalette.Amber),
                )
            pending ->
                Text(
                    "правка в очереди",
                    style = MaterialTheme.typography.bodySmall.copy(color = DocPalette.Amber),
                )
            else -> {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = DocPalette.Green,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    "$path · транскрипт не редактируется",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * «Поделиться» из деталки ушло (решение владельца 2026-08-26 (б)); удаление заметки вместе с её
 * задачами — срез Н6, оно приедет сюда второй кнопкой `danger`.
 */
@Composable
private fun Actions(onOpen: () -> Unit) {
    HorizontalDivider(color = DocPalette.Line, modifier = Modifier.padding(top = 12.dp))
    Row(
        modifier =
            Modifier.fillMaxWidth().padding(16.dp, 12.dp, 16.dp, 8.dp).navigationBarsPadding(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = onOpen,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(
                "Открыть в GitHub",
                color = DocPalette.Ink,
                maxLines = 1,
                softWrap = false,
                style =
                    MaterialTheme.typography.bodySmall.copy(
                        color = DocPalette.Ink,
                        fontWeight = FontWeight.SemiBold,
                    ),
            )
        }
    }
}

private const val POLL_MS = 200L
private const val CHEVRON = 12
private const val CHECKBOX = 19
private const val TRANSCRIPT = "## Транскрипт"
private const val TASKS_BLOCK = "**Задачи"
