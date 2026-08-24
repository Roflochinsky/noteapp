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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roflochinsky.noteapp.pipeline.NotesStore
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Главный экран — лента документов по компу: статус — шёпот, контент главный. */
@Composable
fun FeedScreen(
    notes: List<NotesStore.Note>,
    isRecording: Boolean,
    onNote: (String) -> Unit,
    onRecord: () -> Unit,
    onSettings: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 22.dp, end = 10.dp, top = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Заметки", style = MaterialTheme.typography.headlineSmall)
            IconButton(onClick = onSettings) {
                Icon(Icons.Filled.Settings, "Настройки", tint = DocPalette.Mut)
            }
        }
        if (notes.isEmpty()) {
            EmptyState()
        } else {
            LazyColumn(Modifier.weight(1f)) {
                val groups = notes.groupBy { dayLabel(it.id) }
                groups.forEach { (day, dayNotes) ->
                    item(key = "day-$day") {
                        Text(
                            day.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(start = 22.dp, top = 18.dp, bottom = 4.dp),
                        )
                    }
                    items(dayNotes, key = { it.id }) { note ->
                        NoteItem(note, onClick = { onNote(note.id) })
                        HorizontalDivider(
                            color = DocPalette.Line,
                            modifier = Modifier.padding(horizontal = 22.dp),
                        )
                    }
                }
            }
        }
        RecordBar(isRecording = isRecording, onRecord = onRecord)
    }
}

@Composable
private fun EmptyState() {
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

@Composable
private fun NoteItem(note: NotesStore.Note, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(22.dp, 12.dp)) {
        Text(
            note.title.ifEmpty { "Запись ${timeLabel(note.id)}" },
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
            Text(
                "в очереди — расшифровка",
                style = MaterialTheme.typography.bodySmall.copy(color = DocPalette.Blue),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Row(
            modifier = Modifier.padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(timeLabel(note.id), style = MaterialTheme.typography.labelMedium)
            Text("·", style = MaterialTheme.typography.labelMedium)
            Text(durLabel(note.durationSec), style = MaterialTheme.typography.labelMedium)
            if (note.pushed) {
                Text("·", style = MaterialTheme.typography.labelMedium)
                Text(
                    "✓ в GitHub",
                    style = MaterialTheme.typography.labelMedium.copy(color = DocPalette.Green),
                )
            } else if (note.transcribed) {
                Text("·", style = MaterialTheme.typography.labelMedium)
                Text(
                    "ждёт отправки",
                    style = MaterialTheme.typography.labelMedium.copy(color = DocPalette.Amber),
                )
            }
        }
    }
}

@Composable
private fun RecordBar(isRecording: Boolean, onRecord: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(22.dp, 12.dp, 22.dp, 24.dp),
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

private val idFormat = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

private fun dayLabel(noteId: String): String {
    val d = runCatching { LocalDateTime.parse(noteId, idFormat).toLocalDate() }.getOrNull()
    return when (d) {
        null -> ""
        LocalDate.now() -> "Сегодня"
        LocalDate.now().minusDays(1) -> "Вчера"
        else -> d.format(DateTimeFormatter.ofPattern("d MMMM"))
    }
}

private fun timeLabel(noteId: String): String =
    runCatching {
            LocalDateTime.parse(noteId, idFormat).format(DateTimeFormatter.ofPattern("HH:mm"))
        }
        .getOrDefault("")

private const val SEC_IN_MIN = 60

internal fun durLabel(sec: Long): String {
    val m = sec / SEC_IN_MIN
    val s = sec % SEC_IN_MIN
    return "%02d:%02d".format(m, s)
}
