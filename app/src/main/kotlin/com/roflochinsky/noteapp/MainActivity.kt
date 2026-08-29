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
import androidx.lifecycle.lifecycleScope
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
import com.roflochinsky.noteapp.pipeline.RepoWriteWorker
import com.roflochinsky.noteapp.pipeline.Settings
import com.roflochinsky.noteapp.pipeline.SyncStatus
import com.roflochinsky.noteapp.pipeline.TaskFile
import com.roflochinsky.noteapp.ui.DetailScreen
import com.roflochinsky.noteapp.ui.DocTheme
import com.roflochinsky.noteapp.ui.FeedScreen
import com.roflochinsky.noteapp.ui.OnboardStep
import com.roflochinsky.noteapp.ui.OnboardingScreen
import com.roflochinsky.noteapp.ui.NewTaskSheet
import com.roflochinsky.noteapp.ui.RecordSheet
import com.roflochinsky.noteapp.ui.TaskDetailScreen
import com.roflochinsky.noteapp.ui.Tab
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

        data class Task(val path: String) : Screen
    }

    private var screen by mutableStateOf<Screen>(Screen.Feed())
    private var notes by mutableStateOf(listOf<NotesStore.Note>())
    private var tasks by mutableStateOf(listOf<TaskFile.Task>())
    private var pendingPaths by mutableStateOf(emptySet<String>())
    private var notice by mutableStateOf<String?>(null)
    private var newTaskOpen by mutableStateOf(false)
    private var store: RepoStore? = null
    private var watching = false
    private var sync by mutableStateOf(SyncStatus.OK)
    private var refreshing by mutableStateOf(false)
    private var recording by mutableStateOf(false)
    private var sheetOpen by mutableStateOf(false)
    private var dialog by mutableStateOf<String?>(null) // "deepgram" | "github"
    private var input by mutableStateOf("")
    private var permTick by mutableIntStateOf(0)

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
                            tasksCount = tasks.count { !it.isDone },
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
                            notice = notice,
                            pending = { it in pendingPaths },
                            onTab = { screen = Screen.Feed(it) },
                            onRefresh = { scope.launch { refreshRepo() } },
                            onRecord = ::onRecord,
                            onSettings = { screen = Screen.Onboarding },
                            onTask = { screen = Screen.Task(it) },
                            onNewTask = { newTaskOpen = true },
                            onToggle = { task -> toggle(scope, task) },
                            onCancel = { id -> after(scope) { it.cancel(id) } },
                            onFlush = { RepoWriteWorker.schedule(this@MainActivity) },
                            onNotice = { notice = null },
                        )
                    }
                }
            is Screen.Detail -> {
                BackHandler { screen = Screen.Feed() }
                DetailScreen(noteId = s.noteId, onBack = { screen = Screen.Feed() })
            }
            is Screen.Task -> {
                val back = { screen = Screen.Feed(Tab.TASKS) }
                BackHandler { back() }
                val task = tasks.firstOrNull { it.path == s.path }
                if (task == null) {
                    back()
                } else {
                    TaskDetailScreen(
                        task = task,
                        projects = projects(),
                        pending = s.path in pendingPaths,
                        today = LocalDate.now(),
                        onEdit = { edit -> write(scope) { it.edit(s.path, edit) } },
                        onStatus = { status -> write(scope) { it.setStatus(s.path, status) } },
                        onDelete = {
                            write(scope) { it.delete(s.path) }
                            back()
                        },
                        onOpen = ::openInGithub,
                        onBack = back,
                    )
                }
            }
        }
        if (newTaskOpen) {
            NewTaskSheet(
                projects = projects(),
                today = LocalDate.now(),
                taken = tasks.map { it.path }.toSet(),
                onDismiss = { newTaskOpen = false },
            ) { draft ->
                newTaskOpen = false
                write(scope) {
                    it.create(draft.title, draft.project, draft.priority, draft.due, draft.tags)
                }
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

    private fun projects(): List<String> =
        tasks.mapNotNull { it.project }.distinct().sorted()

    /**
     * Правка кладётся в журнал очереди прямо здесь: это один маленький файл, зато `id` операции
     * нужен снекбару отмены сразу. Тяжёлое (пересбор списка из кэша) уходит на IO.
     */
    private fun toggle(scope: kotlinx.coroutines.CoroutineScope, task: TaskFile.Task): String {
        val store = store ?: return ""
        val status = if (task.isDone) TaskFile.STATUS_OPEN else TaskFile.STATUS_DONE
        val id = store.setStatus(task.path, status)
        scope.launch { reload() }
        return id
    }

    private fun write(scope: kotlinx.coroutines.CoroutineScope, action: (RepoStore) -> Unit) {
        after(scope, action)
        RepoWriteWorker.schedule(this)
    }

    private fun after(scope: kotlinx.coroutines.CoroutineScope, action: (RepoStore) -> Unit) {
        val store = store ?: return
        scope.launch {
            withContext(Dispatchers.IO) { action(store) }
            reload()
        }
    }

    /** Список и «в очереди» пересчитываются из кэша и журнала — вне главного потока. */
    private suspend fun reload() {
        val store = store ?: return
        val fresh =
            withContext(Dispatchers.IO) {
                Triple(store.tasks(), store.pendingPaths(), store.takeDivergence())
            }
        tasks = fresh.first
        pendingPaths = fresh.second
        fresh.third?.let { notice = it }
        watchQueue()
    }

    /** Пока очередь не пуста — тикаем раз в секунду, чтобы янтарь сменился зеленью сам. */
    private fun watchQueue() {
        if (watching || pendingPaths.isEmpty()) return
        watching = true
        lifecycleScope.launch {
            while (pendingPaths.isNotEmpty()) {
                delay(POLL_MS)
                val store = store ?: break
                val fresh =
                    withContext(Dispatchers.IO) {
                        Triple(store.tasks(), store.pendingPaths(), store.takeDivergence())
                    }
                tasks = fresh.first
                pendingPaths = fresh.second
                fresh.third?.let { notice = it }
            }
            watching = false
        }
    }

    private fun openInGithub(path: String) {
        val url =
            "https://github.com/${Settings.githubRepo(this)}/blob/main/" +
                path.split("/").joinToString("/") { android.net.Uri.encode(it) }
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
    }

    private fun onRecord() {
        if (recording) sheetOpen = true
        else startForegroundService(RecordingService.toggleIntent(this))
    }

    /** Кэш рисуется мгновенно, сеть догоняет фоном (решение LLD-12). */
    private suspend fun refreshRepo() {
        val repo = Settings.githubRepo(this)
        val token = Settings.githubToken(this)
        val fresh =
            withContext(Dispatchers.IO) {
                RepoStore(
                    repo = repo,
                    cache = RepoCache(RepoStore.cacheDir(filesDir)),
                    api = token?.let { GithubClient(repo, it) },
                )
            }
        store = fresh
        reload()
        refreshing = true
        sync = withContext(Dispatchers.IO) { fresh.refresh() }
        reload()
        refreshing = false
        // Правки, пережившие перезапуск, доводит тот же воркер — их никто не потерял.
        if (pendingPaths.isNotEmpty()) RepoWriteWorker.schedule(this)
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
