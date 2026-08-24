package com.roflochinsky.noteapp.ui

import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.roflochinsky.noteapp.pipeline.DoneNoteParser
import com.roflochinsky.noteapp.pipeline.GithubClient
import com.roflochinsky.noteapp.pipeline.NotesStore
import com.roflochinsky.noteapp.pipeline.RawNote
import com.roflochinsky.noteapp.pipeline.Settings
import com.roflochinsky.noteapp.pipeline.TranscriptMapper
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Деталка по компу: вкладки Саммари / Транскрипт / Аудио. */
@Composable
fun DetailScreen(noteId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val dir = remember { NotesStore.noteDir(context, noteId) }
    val transcript = remember {
        File(dir, NotesStore.TRANSCRIPT_MD).takeIf { it.exists() }?.readText().orEmpty()
    }
    val marks = remember {
        File(dir, NotesStore.MARKS)
            .takeIf { it.exists() }
            ?.readLines()
            ?.mapNotNull { it.trim().toLongOrNull() }
            .orEmpty()
    }
    var tab by remember { mutableIntStateOf(0) }
    var done by remember { mutableStateOf<DoneNoteParser.DoneNote?>(null) }
    var donePath by remember { mutableStateOf<String?>(null) }
    var doneState by remember { mutableStateOf("загрузка…") }
    LaunchedEffect(noteId) {
        withContext(Dispatchers.IO) {
            val token = Settings.githubToken(context)
            if (token == null) {
                doneState = "нет GitHub-токена"
                return@withContext
            }
            runCatching {
                    val base = RawNote.fileName(noteId).removeSuffix(".md")
                    val path = GithubClient.findDonePath(Settings.githubRepo(context), base, token)
                    donePath = path
                    if (path == null) {
                        doneState = "саммари готовится…"
                    } else {
                        done =
                            DoneNoteParser.parse(
                                GithubClient.readFile(Settings.githubRepo(context), path, token)
                            )
                        if (done == null) doneState = "саммари готовится…"
                    }
                }
                .onFailure { doneState = "не удалось загрузить (${it.message?.take(40)})" }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = DocPalette.Mut)
            }
            Column(Modifier.padding(start = 4.dp)) {
                Text(done?.title ?: "Заметка", style = MaterialTheme.typography.titleMedium)
                val meta = buildList {
                    done?.type?.let(::add)
                    if (done?.participants?.isNotEmpty() == true)
                        add(done!!.participants.joinToString(", "))
                }
                if (meta.isNotEmpty())
                    Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            }
        }
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
                    text = { Text(t, color = if (tab == i) DocPalette.Blue else DocPalette.Mut) },
                )
            }
        }
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> SummaryTab(done, doneState)
                1 -> TranscriptTab(transcript)
                else -> AudioTab(File(dir, NotesStore.AUDIO), marks)
            }
        }
        Actions(donePath, transcript)
    }
}

@Composable
private fun SummaryTab(done: DoneNoteParser.DoneNote?, state: String) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp)) {
        if (done == null) {
            Text(state, style = MaterialTheme.typography.bodySmall)
        } else {
            done.summaryMd.lines().forEach { line ->
                when {
                    line.startsWith("**") -> {
                        val label = line.removePrefix("**").substringBefore("**")
                        Text(
                            label.uppercase().trimEnd('.'),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        )
                        val rest = line.substringAfter("**", "").substringAfter("** ").trim()
                        if (rest.isNotEmpty())
                            Text(rest, style = MaterialTheme.typography.bodyMedium)
                    }
                    line.isNotBlank() ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                }
            }
        }
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
private fun AudioTab(audio: File, marks: List<Long>) {
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

@Composable
private fun Actions(donePath: String?, transcript: String) {
    val context = LocalContext.current
    HorizontalDivider(color = DocPalette.Line)
    Row(
        modifier = Modifier.fillMaxWidth().padding(22.dp, 12.dp, 22.dp, 22.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedButton(
            onClick = {
                val repo = Settings.githubRepo(context)
                val url =
                    if (donePath != null) "https://github.com/$repo/blob/main/$donePath"
                    else "https://github.com/$repo"
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text("Открыть в GitHub", color = DocPalette.Ink)
        }
        OutlinedButton(
            onClick = {
                context.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, transcript),
                        "Поделиться заметкой",
                    )
                )
            },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp),
        ) {
            Icon(Icons.Filled.Share, null, tint = DocPalette.Ink, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Поделиться", color = DocPalette.Ink)
        }
    }
}

private const val POLL_MS = 200L
