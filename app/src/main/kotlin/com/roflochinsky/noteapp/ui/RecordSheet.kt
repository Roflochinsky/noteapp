package com.roflochinsky.noteapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roflochinsky.noteapp.RecordingService
import com.roflochinsky.noteapp.pipeline.TranscriptMapper
import kotlinx.coroutines.delay

/** Шторка записи по компу: таймер, живая волна по амплитуде, флажки, стоп. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordSheet(onMark: () -> Unit, onStop: () -> Unit, onDismiss: () -> Unit) {
    var elapsed by remember { mutableLongStateOf(0L) }
    var amps by remember { mutableStateOf(List(BARS) { 0f }) }
    var marks by remember { mutableStateOf(listOf<Long>()) }
    LaunchedEffect(Unit) {
        while (true) {
            val started = RecordingService.startedAtWallMs
            elapsed = if (started > 0) System.currentTimeMillis() - started else 0
            amps =
                (amps + (RecordingService.currentAmplitude / AMP_MAX).coerceIn(0.06f, 1f)).takeLast(
                    BARS
                )
            marks = RecordingService.marksSnapshot
            delay(TICK_MS)
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = DocPalette.Paper,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(Modifier.padding(26.dp, 0.dp, 26.dp, 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).background(DocPalette.Rec, CircleShape))
                Spacer(Modifier.size(8.dp))
                Text(
                    "ЗАПИСЬ",
                    style = MaterialTheme.typography.labelSmall.copy(color = DocPalette.RecText),
                )
            }
            Text(
                TranscriptMapper.timecode(elapsed).trim('[', ']'),
                fontFamily = FontFamily.Monospace,
                fontSize = 56.sp,
                color = DocPalette.Ink,
                modifier = Modifier.padding(top = 6.dp),
            )
            Wave(amps)
            if (marks.isNotEmpty()) {
                Text(
                    "ОТМЕЧЕННЫЕ МОМЕНТЫ",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 18.dp, bottom = 6.dp),
                )
                marks.forEach {
                    Text(
                        "⚑ ${TranscriptMapper.timecode(it)}",
                        style = MaterialTheme.typography.labelMedium.copy(color = DocPalette.Blue),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 22.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onMark,
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("Отметить момент", color = DocPalette.Nav)
                }
                Button(
                    onClick = onStop,
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = DocPalette.Rec),
                ) {
                    Text("Стоп")
                }
            }
            Text(
                "стоп — кнопкой или снова долгим нажатием питания",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 10.dp).align(Alignment.CenterHorizontally),
            )
        }
    }
}

@Composable
private fun Wave(amps: List<Float>) {
    Canvas(Modifier.fillMaxWidth().height(64.dp).padding(top = 18.dp)) {
        val barW = 4.dp.toPx()
        val gap = (size.width - amps.size * barW) / (amps.size - 1).coerceAtLeast(1)
        amps.forEachIndexed { i, a ->
            val h = (size.height * a).coerceAtLeast(4.dp.toPx())
            val x = i * (barW + gap) + barW / 2
            drawLine(
                color = if (i >= amps.size - HOT_BARS) DocPalette.Rec else DocPalette.Line,
                start = androidx.compose.ui.geometry.Offset(x, size.height / 2 - h / 2),
                end = androidx.compose.ui.geometry.Offset(x, size.height / 2 + h / 2),
                strokeWidth = barW,
                cap = StrokeCap.Round,
            )
        }
    }
}

private const val BARS = 36
private const val HOT_BARS = 3
private const val AMP_MAX = 22000f
private const val TICK_MS = 150L
