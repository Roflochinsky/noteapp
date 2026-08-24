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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File

/** Служебный экран статуса зонда — без дизайна, комп «Документ» тут не применяется. */
class MainActivity : ComponentActivity() {

    private var statusText by mutableStateOf("")

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("noteapp probe", style = MaterialTheme.typography.headlineSmall)
                        Text(statusText, style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { requestPermissions() }) { Text("Запросить разрешения") }
                        Button(
                            onClick = {
                                startForegroundService(
                                    RecordingService.toggleIntent(this@MainActivity)
                                )
                            }
                        ) {
                            Text("Toggle вручную")
                        }
                        Button(onClick = { refreshStatus() }) { Text("Обновить статус") }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun requestPermissions() {
        permissionLauncher.launch(
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        )
    }

    private fun refreshStatus() {
        val role = getSystemService(RoleManager::class.java)
        val roleHeld = role?.isRoleHeld(RoleManager.ROLE_ASSISTANT) ?: false
        val micGranted =
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        val files =
            File(filesDir, RecordingService.PROBE_DIR)
                .listFiles()
                .orEmpty()
                .sortedByDescending { it.name }
                .joinToString("\n") { "  ${it.name} · ${it.length()} байт" }
                .ifEmpty { "  (записей нет)" }
        statusText =
            listOf(
                    "роль ассистента: ${if (roleHeld) "наша" else "НЕ наша"}",
                    "микрофон: ${if (micGranted) "разрешён" else "НЕ разрешён"}",
                    "запись сейчас: ${if (RecordingService.isRunning) "идёт" else "нет"}",
                    "файлы probe:",
                    files,
                )
                .joinToString("\n")
    }
}
