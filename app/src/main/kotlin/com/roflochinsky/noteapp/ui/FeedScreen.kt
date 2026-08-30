package com.roflochinsky.noteapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roflochinsky.noteapp.pipeline.FeedItem
import com.roflochinsky.noteapp.pipeline.SyncStatus
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Главный экран — лента документов по компу (борд 5): статус — шёпот, контент главный.
 *
 * Лента одна на два источника: локальные записи телефона и заметки из кэша репо, склеенные по
 * `NoteRef` (решение LLD-11). Поэтому запись без сети видна со статусом, а когда её файл доедет и
 * Claude допишет саммари — та же строка становится документом с заголовком, а не появляется второй.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    feed: List<FeedItem>,
    people: List<String>,
    projects: List<String>,
    today: LocalDate,
    isRecording: Boolean,
    tasksCount: Int,
    sync: SyncStatus,
    refreshing: Boolean,
    notice: String? = null,
    onNotice: () -> Unit = {},
    onTab: (Tab) -> Unit,
    onNote: (String) -> Unit,
    onRefresh: () -> Unit,
    onRecord: () -> Unit,
    onSettings: () -> Unit,
) {
    val pull = rememberPullToRefreshState()
    // Состояние чипов и поиска переживает поворот, но не перезапуск (решение LLD-17).
    var filter by
        rememberSaveable(stateSaver = FeedFilterSaver) { mutableStateOf(NoteFilter.Filter()) }
    var searching by rememberSaveable { mutableStateOf(false) }
    val shown = filter.select(feed, today)
    Column(Modifier.fillMaxSize()) {
        SectionTabs(
            active = Tab.NOTES,
            tasksCount = tasksCount,
            onTab = onTab,
            onSettings = onSettings,
            query = filter.query.takeIf { searching },
            onQuery = { text ->
                searching = text != null
                filter = filter.copy(query = text.orEmpty())
            },
        )
        NoteChips(feed, people, projects, today, filter) { filter = it }
        // Первый синк уходит на этой вкладке — отказ («нет токена», «нет доступа») виден здесь же.
        SyncLine(sync, onSettings)
        // Расхождение по 409 — та же непрерывающая плашка, что и на задачах: правка поля заметки
        // уходит той же очередью, и молчать о её отказе до перехода на другую вкладку нельзя.
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
            NoteList(shown, filter.active, onNote) {
                filter = NoteFilter.Filter()
                searching = false
            }
        }
        RecordBar(isRecording = isRecording, onRecord = onRecord)
    }
}

@Composable
private fun NoteList(
    feed: List<FeedItem>,
    filtered: Boolean,
    onNote: (String) -> Unit,
    onReset: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        // Пусто под фильтром — не то же, что пустая лента: заметки есть, просто не подошли, и
        // выход из этого состояния должен быть одним тапом (вердикт UX).
        if (feed.isEmpty()) {
            item { if (filtered) EmptyFiltered(onReset) else EmptyFeed() }
        }
        feed
            .groupBy { dayLabel(it.time) }
            .forEach { (day, dayNotes) ->
                item(key = "day-$day") {
                    Text(
                        day.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(start = 22.dp, top = 18.dp, bottom = 4.dp),
                    )
                }
                // Ключ — `note.key`, а НЕ `note.ref`: ref точен до минуты (он ключ склейки), и две
                // записи в одну минуту роняли список `IllegalArgumentException` (блокер ревью Н5).
                itemsIndexed(dayNotes, key = { _, note -> note.key }) { i, note ->
                    // Комп: `.doc-item + .doc-item{border-top}` — линия висит на самой строке
                    // с её паддингом, то есть идёт от края до края, и только между соседями:
                    // под последней записью дня и над рубрикой следующего её нет. Так же
                    // разделены строки задач; `DESIGN.md` требует «full-bleed строки,
                    // разделённые hairline 1px».
                    if (i > 0) {
                        HorizontalDivider(color = DocPalette.Line)
                    }
                    NoteItem(note, onClick = { onNote(note.key) })
                }
            }
    }
}

@Composable
private fun EmptyFeed() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        EmptyIllustration()
        Text("Пока ни одной заметки", style = MaterialTheme.typography.titleMedium)
        Text(
            "Зажми кнопку питания — и говори.\nОстальное случится само.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 40.dp),
        )
    }
}

/**
 * ponytail: близнец пустого состояния задач; свести — вместе с чипами, `bd nikitatrubaev-0rk.29`.
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

/**
 * Строка-документ: заголовок, превью, мета. Мета по компу — время · длительность · тип · персоны ·
 * доставка; цифры моно, слова обычным (правило моно-цифр DESIGN.md).
 */
@Composable
private fun NoteItem(note: FeedItem, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(22.dp, 12.dp)) {
        Text(
            note.title.ifEmpty { "Запись ${timeLabel(note.time)}" },
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (note.transcribed) {
            Text(
                note.preview,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            // Очередь — янтарь, как и вся очередь этого мира (вердикт UX; было синим).
            Text(
                "в очереди — расшифровка",
                style = MaterialTheme.typography.bodySmall.copy(color = DocPalette.Amber),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Meta(note)
    }
}

@Composable
private fun Meta(note: FeedItem) {
    Row(
        modifier = Modifier.padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(timeLabel(note.time), style = MaterialTheme.typography.labelMedium)
        Dot()
        Text(durLabel(note.durationSec), style = MaterialTheme.typography.labelMedium)
        val words =
            listOfNotNull(
                    note.type,
                    note.participants.joinToString(", ").takeIf { it.isNotEmpty() },
                )
                .joinToString(" · ")
        if (words.isNotEmpty()) {
            Dot()
            Text(words, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
        if (note.pushed) {
            Dot()
            Text(
                "✓ в GitHub",
                style = MaterialTheme.typography.bodySmall.copy(color = DocPalette.Green),
            )
        } else if (note.transcribed) {
            Dot()
            Text(
                "ждёт отправки",
                style = MaterialTheme.typography.bodySmall.copy(color = DocPalette.Amber),
            )
        }
    }
}

@Composable
private fun Dot() {
    Text("·", style = MaterialTheme.typography.labelMedium)
}

/** Панель записи одна на оба раздела: идущая запись важнее любой другой нижней кнопки. */
@Composable
internal fun RecordBar(isRecording: Boolean, onRecord: () -> Unit) {
    Column(
        modifier =
            Modifier.fillMaxWidth().padding(22.dp, 12.dp, 22.dp, 8.dp).navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Button(
            onClick = onRecord,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(14.dp),
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = if (isRecording) DocPalette.Rec else DocPalette.Nav
                ),
        ) {
            Box(
                Modifier.size(10.dp)
                    .background(if (isRecording) DocPalette.Paper else DocPalette.Rec, CircleShape)
            )
            Spacer(Modifier.size(10.dp))
            Text(if (isRecording) "Идёт запись — открыть" else "Записать")
        }
        Text(
            "или долгое нажатие кнопки питания — даже с заблокированного экрана",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

private val FeedFilterSaver =
    listSaver<NoteFilter.Filter, String?>(
        save = { listOf(it.project, it.type, it.person, it.tag, it.date, it.query) },
        restore = { NoteFilter.Filter(it[0], it[1], it[2], it[3], it[4], it[5].orEmpty()) },
    )

/**
 * Названия месяцев свои, как и у задач ([TaskFilter]): формат «24 августа» зависел бы от локали
 * устройства, а мир приложения по-русски всегда.
 */
private val MONTHS =
    listOf(
        "января",
        "февраля",
        "марта",
        "апреля",
        "мая",
        "июня",
        "июля",
        "августа",
        "сентября",
        "октября",
        "ноября",
        "декабря",
    )

private fun dayLabel(time: LocalDateTime?): String {
    val day = time?.toLocalDate() ?: return ""
    return when (day) {
        LocalDate.now() -> "Сегодня"
        LocalDate.now().minusDays(1) -> "Вчера"
        else -> "${day.dayOfMonth} ${MONTHS[day.monthValue - 1]}"
    }
}

private fun timeLabel(time: LocalDateTime?): String =
    time?.format(DateTimeFormatter.ofPattern("HH:mm")).orEmpty()

private const val SEC_IN_MIN = 60

internal fun durLabel(sec: Long): String {
    val m = sec / SEC_IN_MIN
    val s = sec % SEC_IN_MIN
    return "%02d:%02d".format(m, s)
}
