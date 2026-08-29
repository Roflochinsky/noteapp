package com.roflochinsky.noteapp

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as SysSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.roflochinsky.noteapp.pipeline.GithubClient
import com.roflochinsky.noteapp.pipeline.NotesStore
import com.roflochinsky.noteapp.pipeline.RepoCache
import com.roflochinsky.noteapp.pipeline.RepoStore
import com.roflochinsky.noteapp.pipeline.Settings
import com.roflochinsky.noteapp.pipeline.SyncStatus
import com.roflochinsky.noteapp.pipeline.TaskFile
import com.roflochinsky.noteapp.ui.DetailScreen
import com.roflochinsky.noteapp.ui.DocTheme
import com.roflochinsky.noteapp.ui.FeedScreen
import com.roflochinsky.noteapp.ui.OnboardStep
import com.roflochinsky.noteapp.ui.OnboardingScreen
import com.roflochinsky.noteapp.ui.RecordSheet
import com.roflochinsky.noteapp.ui.Tab
import com.roflochinsky.noteapp.ui.TaskFilter
import com.roflochinsky.noteapp.ui.TasksScreen
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private sealed interface Screen {
        data object Onboarding : Screen

        /** Вкладка — часть состояния экрана: Back с «Задач» возвращает на «Заметки». */
        data class Feed(val tab: Tab = Tab.NOTES) : Screen

        data class Detail(val noteId: String) : Screen
    }

    private var screen by mutableStateOf<Screen>(Screen.Feed())
    private var notes by mutableStateOf(listOf<NotesStore.Note>())
    private var tasks by mutableStateOf(listOf<TaskFile.Task>())
    private var sync by mutableStateOf(SyncStatus.OK)
    private var refreshing by mutableStateOf(false)
    private var recording by mutableStateOf(false)
    private var sheetOpen by mutableStateOf(false)
    private var dialog by mutableStateOf<String?>(null) // "deepgram" | "github"
    private var input by mutableStateOf("")
    private var permTick by mutableIntStateOf(0)
    private var repoStore: RepoStore? = null
    private var repoKey: String? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            permTick++
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = true
        if (!setupComplete()) screen = Screen.Onboarding
        setContent {
            DocTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    androidx.compose.foundation.layout.Box(
                        androidx.compose.foundation.layout.WindowInsets.statusBars.let {
                            Modifier.windowInsetsPadding(it)
                        }
                    ) {
                        Root()
                    }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Root() {
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        // Секундный цикл — только локальные записи и состояние диктофона; репо в него не мешаем.
        LaunchedEffect(Unit) {
            while (true) {
                notes = NotesStore.list(this@MainActivity)
                val was = recording
                recording = RecordingService.isRunning
                if (recording && !was) sheetOpen = true
                if (!recording) sheetOpen = false
                delay(POLL_MS)
            }
        }
        LaunchedEffect(Unit) { refreshRepo() }
        when (val s = screen) {
            is Screen.Onboarding -> OnboardingScreen(steps = steps()) { screen = Screen.Feed() }
            is Screen.Feed ->
                when (s.tab) {
                    Tab.NOTES ->
                        FeedScreen(
                            notes = notes,
                            isRecording = recording,
                            tasksCount = TaskFilter.openCount(tasks),
                            sync = sync,
                            onTab = { screen = Screen.Feed(it) },
                            onNote = { screen = Screen.Detail(it) },
                            onRecord = ::onRecord,
                            onSettings = { screen = Screen.Onboarding },
                        )
                    Tab.TASKS -> {
                        BackHandler { screen = Screen.Feed(Tab.NOTES) }
                        TasksScreen(
                            tasks = tasks,
                            today = LocalDate.now(),
                            sync = sync,
                            refreshing = refreshing,
                            isRecording = recording,
                            onTab = { screen = Screen.Feed(it) },
                            onRefresh = { scope.launch { refreshRepo() } },
                            onRecord = ::onRecord,
                            onSettings = { screen = Screen.Onboarding },
                        )
                    }
                }
            is Screen.Detail -> {
                BackHandler { screen = Screen.Feed() }
                DetailScreen(noteId = s.noteId, onBack = { screen = Screen.Feed() })
            }
        }
        if (screen is Screen.Onboarding && setupComplete()) {
            BackHandler { screen = Screen.Feed() }
        }
        if (sheetOpen && recording) {
            RecordSheet(
                onMark = { sendAction(RecordingService.ACTION_MARK) },
                onStop = {
                    sendAction(RecordingService.ACTION_STOP)
                    sheetOpen = false
                },
                onDismiss = { sheetOpen = false },
            )
        }
        when (dialog) {
            "deepgram" ->
                InputDialog("Ключ Deepgram") { Settings.setDeepgramKey(this@MainActivity, it) }
            "github" ->
                InputDialog("GitHub-токен (репо заметок)") {
                    Settings.setGithubToken(this@MainActivity, it)
                }
        }
    }

    private fun onRecord() {
        if (recording) sheetOpen = true
        else startForegroundService(RecordingService.toggleIntent(this))
    }

    /**
     * Кэш рисуется мгновенно, сеть догоняет фоном (решение LLD-12). Индикатор поднимается первой
     * строкой — до любого прыжка в IO, иначе он включался уже после того, как всё прочитано; гаснет
     * в `finally`, чтобы сорвавшийся `refresh()` не оставил вертушку крутиться навсегда.
     *
     * `tasks()` парсит каждый файл кэша, поэтому обе выборки живут в IO: главный поток только
     * рисует.
     */
    private suspend fun refreshRepo() {
        refreshing = true
        try {
            val store = store()
            tasks = withContext(Dispatchers.IO) { store.tasks() }
            val (status, fresh) = withContext(Dispatchers.IO) { store.refresh() to store.tasks() }
            sync = status
            tasks = fresh
        } finally {
            refreshing = false
        }
    }

    /**
     * Один фасад на сессию: он держит снимок кэша, а пересоздание на каждое обновление этот снимок
     * выбрасывало. Ключ — репо и токен: подключили GitHub в настройках — фасад пересоберётся сам.
     */
    private suspend fun store(): RepoStore {
        val repo = Settings.githubRepo(this)
        val token = Settings.githubToken(this)
        val key = "$repo:${token?.hashCode() ?: 0}"
        repoStore
            ?.takeIf { repoKey == key }
            ?.let {
                return it
            }
        return withContext(Dispatchers.IO) {
                RepoStore(
                    cache = RepoCache(RepoStore.cacheDir(filesDir), repo, token),
                    api = token?.let { GithubClient(repo, it) },
                )
            }
            .also {
                repoStore = it
                repoKey = key
            }
    }

    private fun sendAction(action: String) {
        startForegroundService(Intent(this, RecordingService::class.java).setAction(action))
    }

    @androidx.compose.runtime.Composable
    private fun InputDialog(title: String, onSave: (String) -> Unit) {
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(title) },
            text = {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSave(input)
                        dialog = null
                        permTick++
                    }
                ) {
                    Text("Сохранить")
                }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Отмена") } },
        )
    }

    private fun steps(): List<OnboardStep> {
        permTick // перечитывать при изменениях
        val role =
            getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT)
                ?: false
        val mic =
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        val notif =
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        val battery =
            getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName)
                ?: false
        return listOf(
            OnboardStep("Назначить ассистентом", "вместо Gemini по кнопке питания", role) {
                startActivity(Intent(SysSettings.ACTION_VOICE_INPUT_SETTINGS))
            },
            OnboardStep("Микрофон и уведомления", "разрешения записи", mic && notif) {
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.POST_NOTIFICATIONS,
                    )
                )
            },
            OnboardStep(
                "Подключить GitHub",
                "токен приватного репозитория заметок",
                Settings.githubToken(this) != null,
            ) {
                input = Settings.githubToken(this).orEmpty()
                dialog = "github"
            },
            OnboardStep(
                "Ключ Deepgram",
                "расшифровка и спикеры",
                Settings.deepgramKey(this) != null,
            ) {
                input = Settings.deepgramKey(this).orEmpty()
                dialog = "deepgram"
            },
            OnboardStep(
                "Батарея без ограничений",
                "чтобы длинная запись не умирала в фоне",
                battery,
            ) {
                startActivity(Intent(SysSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            },
        )
    }

    private fun setupComplete(): Boolean {
        val role =
            getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT)
                ?: false
        val mic =
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        return role &&
            mic &&
            Settings.deepgramKey(this) != null &&
            Settings.githubToken(this) != null
    }

    override fun onResume() {
        super.onResume()
        permTick++
    }

    private companion object {
        const val POLL_MS = 1000L
    }
}
