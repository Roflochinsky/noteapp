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
import androidx.lifecycle.lifecycleScope
import com.roflochinsky.noteapp.pipeline.GithubClient
import com.roflochinsky.noteapp.pipeline.NoteFile
import com.roflochinsky.noteapp.pipeline.NoteRef
import com.roflochinsky.noteapp.pipeline.NotesStore
import com.roflochinsky.noteapp.pipeline.PipelineQueue
import com.roflochinsky.noteapp.pipeline.RepoStore
import com.roflochinsky.noteapp.pipeline.RepoWriteWorker
import com.roflochinsky.noteapp.pipeline.Settings
import com.roflochinsky.noteapp.pipeline.SyncStatus
import com.roflochinsky.noteapp.pipeline.TaskFile
import com.roflochinsky.noteapp.ui.DetailScreen
import com.roflochinsky.noteapp.ui.DocTheme
import com.roflochinsky.noteapp.ui.FeedScreen
import com.roflochinsky.noteapp.ui.NewTaskSheet
import com.roflochinsky.noteapp.ui.OnboardStep
import com.roflochinsky.noteapp.ui.OnboardingScreen
import com.roflochinsky.noteapp.ui.RecordSheet
import com.roflochinsky.noteapp.ui.Tab
import com.roflochinsky.noteapp.ui.TaskDetailScreen
import com.roflochinsky.noteapp.ui.TaskFilter
import com.roflochinsky.noteapp.ui.TasksScreen
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Suppress("TooManyFunctions") // точка сборки экранов; разбор — bd nikitatrubaev-0rk.14
class MainActivity : ComponentActivity() {

    private sealed interface Screen {
        data object Onboarding : Screen

        /** Вкладка — часть состояния экрана: Back с «Задач» возвращает на «Заметки». */
        data class Feed(val tab: Tab = Tab.NOTES) : Screen

        /**
         * Заметка открывается по ключу строки ленты (`FeedItem.key`) — уникальному, в отличие от
         * `ref`: у неё может не быть ни записи, ни файла в репо, но одна из двух сторон есть
         * всегда, и она же даёт ключ.
         */
        data class Detail(val key: String) : Screen

        data class Task(val path: String) : Screen
    }

    private var screen by mutableStateOf<Screen>(Screen.Feed())
    private var notes by mutableStateOf(listOf<NotesStore.Note>())
    private var tasks by mutableStateOf(listOf<TaskFile.Task>())
    private var pendingPaths by mutableStateOf(emptySet<String>())
    private var registry by mutableStateOf(listOf<String>())
    private var repoNotes by mutableStateOf(listOf<NoteFile.Note>())
    private var people by mutableStateOf(listOf<String>())
    private var revision = ""
    private var notice by mutableStateOf<String?>(null)
    private var newTaskOpen by mutableStateOf(false)
    private var watching = false
    private var sync by mutableStateOf(SyncStatus.OK)
    private var refreshing by mutableStateOf(false)
    private var recording by mutableStateOf(false)
    private var sheetOpen by mutableStateOf(false)
    private var dialog by mutableStateOf<String?>(null) // "deepgram" | "github"
    private var input by mutableStateOf("")
    private var permTick by mutableIntStateOf(0)
    private var repoStore: RepoStore? = null

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
                            feed = feed(),
                            people = people,
                            projects = projects(),
                            today = LocalDate.now(),
                            isRecording = recording,
                            tasksCount = TaskFilter.openCount(tasks),
                            sync = sync,
                            refreshing = refreshing,
                            notice = notice,
                            onNotice = { notice = null },
                            onTab = { screen = Screen.Feed(it) },
                            onNote = { screen = Screen.Detail(it) },
                            onRefresh = { scope.launch { refreshRepo() } },
                            onRecord = ::onRecord,
                            onSettings = { screen = Screen.Onboarding },
                        )
                    Tab.TASKS -> {
                        BackHandler { screen = Screen.Feed(Tab.NOTES) }
                        TasksScreen(
                            tasks = tasks,
                            projects = projects(),
                            today = LocalDate.now(),
                            sync = sync,
                            refreshing = refreshing,
                            isRecording = recording,
                            notice = notice,
                            pending = { it in pendingPaths },
                            onTab = { screen = Screen.Feed(it) },
                            // Жест владельца — безусловный запрос (граница Н7): открытие
                            // приложения обходится условным и почти всегда бесплатным.
                            onRefresh = { scope.launch { refreshRepo(force = true) } },
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
                val back = { screen = Screen.Feed() }
                BackHandler { back() }
                val item = feed().firstOrNull { it.key == s.key }
                if (item == null) {
                    // Заметку унесло обновлением (Action перенёс, владелец удалил) — уходим на
                    // ленту эффектом: писать в state прямо в теле композиции нельзя.
                    LaunchedEffect(s.key) { back() }
                } else {
                    DetailScreen(
                        item = item,
                        people = people,
                        projects = projects(),
                        tasks = tasks,
                        pending = item.path in pendingPaths,
                        onEdit = { edit ->
                            item.path?.let { write(scope) { s -> s.edit(it, edit) } }
                        },
                        onOpen = ::openInGithub,
                        onTask = { screen = Screen.Task(it) },
                        onRetry = {
                            item.noteId?.let { PipelineQueue.enqueue(this@MainActivity, it) }
                        },
                        onBack = back,
                    )
                }
            }
            is Screen.Task -> {
                val back = { screen = Screen.Feed(Tab.TASKS) }
                BackHandler { back() }
                val task = tasks.firstOrNull { it.path == s.path }
                if (task == null) {
                    // Задачи больше нет (удалили или унесло обновлением): уходим на список
                    // эффектом — писать в state прямо в теле композиции нельзя.
                    LaunchedEffect(s.path) { back() }
                } else {
                    TaskDetailScreen(
                        task = task,
                        projects = projects(),
                        pending = s.path in pendingPaths,
                        today = LocalDate.now(),
                        notice = notice,
                        onNotice = { notice = null },
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
                // Реестр пополняется отдельной операцией: проект живёт дольше задачи, ради которой
                // его завели, и остаётся в `projects.md`, даже если задачу потом удалят.
                onNewProject = { name -> write(scope) { it.addProject(name) } },
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

    /**
     * Значения проекта: сперва реестр `projects.md` в его собственном порядке (так он нарисован в
     * компе), следом — проекты, которые уже стоят в задачах, но в реестр ещё не попали. Задача с
     * незнакомым проектом иначе выпала бы и из фильтра, и из выбора в деталке.
     */
    /** Лента — записи телефона ∪ заметки из кэша репо, склеенные по `NoteRef` (решение LLD-11). */
    private fun feed(): List<com.roflochinsky.noteapp.pipeline.FeedItem> =
        NoteRef.merge(notes, repoNotes)

    private fun projects(): List<String> =
        (registry + tasks.mapNotNull { it.project }.sorted()).distinct()

    /**
     * Правка кладётся в журнал очереди прямо здесь: это один маленький файл, зато `id` операции
     * нужен снекбару отмены сразу. Тяжёлое (пересбор списка из кэша) уходит на IO.
     */
    private fun toggle(scope: kotlinx.coroutines.CoroutineScope, task: TaskFile.Task): String {
        val store = repoStore ?: return ""
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
        val store = repoStore ?: return
        scope.launch {
            withContext(Dispatchers.IO) { action(store) }
            reload()
        }
    }

    /** Список и «в очереди» пересчитываются из кэша и журнала — вне главного потока. */
    private suspend fun reload() {
        val store = repoStore ?: return
        show(withContext(Dispatchers.IO) { store.view() })
        watchQueue()
    }

    private fun show(fresh: RepoStore.View) {
        revision = fresh.revision
        tasks = fresh.tasks
        pendingPaths = fresh.pending
        registry = fresh.projects
        repoNotes = fresh.notes
        people = fresh.people
        fresh.notice?.let { notice = it }
    }

    /**
     * Пока очередь не пуста — тикаем раз в секунду, чтобы янтарь сменился зеленью сам. Репо-кэш в
     * секундный поллинг не попадает (вердикт UX): тик читает дешёвую метку и пересобирает список
     * задач, только если кэш или журнал действительно изменились.
     */
    private fun watchQueue() {
        if (watching || pendingPaths.isEmpty()) return
        watching = true
        lifecycleScope.launch {
            while (pendingPaths.isNotEmpty()) {
                delay(POLL_MS)
                val store = repoStore ?: break
                // Метка дешёвая: каталог очереди и время файла кэша. Не сменилась — не парсим.
                val fresh =
                    withContext(Dispatchers.IO) {
                        if (store.revision() == revision) null else store.view()
                    }
                fresh?.let { show(it) }
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

    /**
     * Кэш рисуется мгновенно, сеть догоняет фоном (решение LLD-12). Индикатор поднимается первой
     * строкой — до любого прыжка в IO, иначе он включался уже после того, как всё прочитано; гаснет
     * в `finally`, чтобы сорвавшийся `refresh()` не оставил вертушку крутиться навсегда.
     *
     * `tasks()` парсит каждый файл кэша, поэтому обе выборки живут в IO: главный поток только
     * рисует.
     */
    private suspend fun refreshRepo(force: Boolean = false) {
        refreshing = true
        try {
            store()
            reload()
            sync = withContext(Dispatchers.IO) { repoStore?.refresh(force) } ?: SyncStatus.NO_TOKEN
            reload()
        } finally {
            refreshing = false
        }
    }

    /**
     * Один фасад на процесс — общий с воркером записи ([RepoStore.shared]): он держит снимок кэша,
     * а второй экземпляр над тем же кэшем перетирал чужую запись (блокер Б1). Ключ — репо и токен:
     * подключили GitHub в настройках — фасад пересоберётся сам.
     *
     * Долг Н-8 ревью Н1: `Settings` — это SharedPreferences, то есть диск; читаются они внутри
     * `withContext(Dispatchers.IO)`, главный поток только рисует.
     */
    private suspend fun store(): RepoStore {
        val fresh =
            withContext(Dispatchers.IO) {
                val repo = Settings.githubRepo(this@MainActivity)
                val token = Settings.githubToken(this@MainActivity)
                RepoStore.shared(
                    cacheDir = RepoStore.cacheDir(filesDir),
                    repo = repo,
                    token = token,
                    api = token?.let { GithubClient(repo, it) },
                )
            }
        if (fresh !== repoStore) {
            repoStore = fresh
            // Правки, пережившие перезапуск, доводит тот же воркер — их никто не потерял.
            if (withContext(Dispatchers.IO) { fresh.pendingPaths().isNotEmpty() }) {
                RepoWriteWorker.schedule(this)
            }
        }
        return fresh
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
