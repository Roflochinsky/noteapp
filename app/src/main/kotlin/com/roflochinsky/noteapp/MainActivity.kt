package com.roflochinsky.noteapp

import android.Manifest
import android.app.role.RoleManager
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.roflochinsky.noteapp.pipeline.NotesStore
import com.roflochinsky.noteapp.pipeline.Settings
import com.roflochinsky.noteapp.pipeline.TranscribeWorker

/** Служебный экран (дизайн-лента придёт срезом С5): статус, заметки, ключ Deepgram. */
class MainActivity : ComponentActivity() {

    private var statusText by mutableStateOf("")
    private var notes by mutableStateOf(listOf<NotesStore.Note>())
    private var keyDialog by mutableStateOf(false)
    private var keyInput by mutableStateOf("")

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("MyNoteBook", style = MaterialTheme.typography.headlineSmall)
                        Text(statusText, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { requestPermissions() }) { Text("Разрешения") }
                            Button(
                                onClick = {
                                    startForegroundService(
                                        RecordingService.toggleIntent(this@MainActivity)
                                    )
                                }
                            ) {
                                Text("Toggle")
                            }
                            Button(
                                onClick = {
                                    keyInput = Settings.deepgramKey(this@MainActivity).orEmpty()
                                    keyDialog = true
                                }
                            ) {
                                Text("Ключ STT")
                            }
                        }
                        HorizontalDivider()
                        // ponytail: пока есть нерасшифрованные — перечитываем каждые 2с;
                        // нормальная подписка на WorkManager придёт с лентой С5.
                        LaunchedEffect(notes.any { !it.transcribed }) {
                            while (notes.any { !it.transcribed }) {
                                kotlinx.coroutines.delay(POLL_MS)
                                refresh()
                            }
                        }
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(notes, key = { it.id }) { note -> NoteRow(note) }
                        }
                    }
                    if (keyDialog) KeyDialog()
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun KeyDialog() {
        AlertDialog(
            onDismissRequest = { keyDialog = false },
            title = { Text("Ключ Deepgram") },
            text = {
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        Settings.setDeepgramKey(this@MainActivity, keyInput)
                        keyDialog = false
                        refresh()
                    }
                ) {
                    Text("Сохранить")
                }
            },
            dismissButton = { TextButton(onClick = { keyDialog = false }) { Text("Отмена") } },
        )
    }

    @androidx.compose.runtime.Composable
    private fun NoteRow(note: NotesStore.Note) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(note.id, style = MaterialTheme.typography.titleSmall)
                if (!note.transcribed) {
                    TextButton(onClick = { TranscribeWorker.enqueue(this@MainActivity, note.id) }) {
                        Text("Расшифровать")
                    }
                }
            }
            Text(
                if (note.transcribed) note.preview else "расшифровка не готова",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun requestPermissions() {
        permissionLauncher.launch(
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        )
    }

    private companion object {
        const val POLL_MS = 2000L
    }

    private fun refresh() {
        val role = getSystemService(RoleManager::class.java)
        val roleHeld = role?.isRoleHeld(RoleManager.ROLE_ASSISTANT) ?: false
        val micGranted =
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        val hasKey = Settings.deepgramKey(this) != null
        statusText =
            "роль: ${if (roleHeld) "наша" else "НЕ наша"} · мик: " +
                "${if (micGranted) "да" else "НЕТ"} · запись: " +
                "${if (RecordingService.isRunning) "идёт" else "нет"} · ключ STT: " +
                if (hasKey) "есть" else "НЕТ"
        notes = NotesStore.list(this)
    }
}
