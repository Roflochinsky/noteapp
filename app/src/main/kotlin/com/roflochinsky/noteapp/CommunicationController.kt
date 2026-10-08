package com.roflochinsky.noteapp

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.roflochinsky.noteapp.pipeline.Communication
import com.roflochinsky.noteapp.pipeline.CommunicationStore
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** Owns only local text and user-selected document/share operations, never the sync pipeline. */
class CommunicationController(
    private val activity: ComponentActivity,
    private val onOpen: () -> Unit,
) {
    var sources by mutableStateOf(emptyList<Communication>())
        private set

    var incoming by mutableStateOf<Communication?>(null)
    var error by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
        private set

    private var exportFile: File? = null
    private var consumed = false
    private val mutex = Mutex()
    private val store = CommunicationStore.onDevice(activity)
    private val importer =
        activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null)
                work {
                    val text =
                        withContext(Dispatchers.IO) { CommunicationFiles.read(activity, uri) }
                    incoming = Communication(title = "Импортированный текст", text = text)
                    onOpen()
                }
        }
    private val exporter =
        activity.registerForActivityResult(
            ActivityResultContracts.CreateDocument("text/markdown")
        ) { uri ->
            val file = exportFile
            if (uri != null && file != null)
                work {
                    withContext(Dispatchers.IO) {
                        activity.contentResolver.openOutputStream(uri)?.use { output ->
                            file.inputStream().use { it.copyTo(output) }
                        } ?: error("Не удалось сохранить файл")
                    }
                    error = "Копия контекста сохранена в выбранном месте."
                }
        }

    fun restore(state: Bundle?, intent: Intent) {
        state?.getString("exportFile")?.let { name ->
            if (name.matches(Regex("context-[a-zA-Z0-9-]+\\.md")))
                exportFile = File(activity.cacheDir, "context-exports/$name")
        }
        work { sources = withContext(Dispatchers.IO) { store.list() } }
        consumed = state?.getBoolean("sharedInputConsumed") ?: false
        state?.getBundle("incomingCommunication")?.let { item ->
            incoming =
                Communication(
                    id = item.getString("id")!!,
                    title = item.getString("title").orEmpty(),
                    text = item.getString("text").orEmpty(),
                )
        }
        if (!consumed) receive(intent)
    }

    fun saveState(state: Bundle) {
        state.putBoolean("sharedInputConsumed", consumed)
        incoming?.let { source ->
            state.putBundle(
                "incomingCommunication",
                Bundle().apply {
                    putString("id", source.id)
                    putString("title", source.title)
                    putString("text", source.text)
                },
            )
        }
        exportFile?.let { state.putString("exportFile", it.name) }
    }

    fun receive(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND || intent.type?.startsWith("text/") != true) return
        consumed = false
        onOpen()
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        if (text == null) {
            val uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
            if (uri == null || uri.scheme != "content") {
                error = "Не найден текст. Выберите UTF-8 .txt / .md через «Импорт»."
                consume(intent)
                return
            }
            work {
                try {
                    val imported =
                        withContext(Dispatchers.IO) { CommunicationFiles.read(activity, uri) }
                    incoming = Communication(title = "Полученный файл", text = imported)
                } finally {
                    if (currentCoroutineContext().isActive) consume(intent)
                }
            }
            return
        }
        if (text.length > CommunicationStore.MAX_TEXT) {
            error = "Текст слишком большой: максимум 100 000 символов. Разделите источник."
        } else {
            incoming =
                Communication(
                    title =
                        intent
                            .getStringExtra(Intent.EXTRA_SUBJECT)
                            ?.take(CommunicationStore.MAX_TITLE) ?: "Полученный текст",
                    text = text,
                )
        }
        consume(intent)
    }

    private fun consume(intent: Intent) {
        if (activity.intent === intent) consumed = true
        intent.action = null
    }

    fun importFile() = importer.launch(arrayOf("text/plain", "text/markdown", "text/x-markdown"))

    fun save(value: Communication, completed: () -> Unit) = work {
        withContext(Dispatchers.IO) { store.save(value) }
        sources = withContext(Dispatchers.IO) { store.list() }
        completed()
    }

    fun export(share: Boolean, content: () -> String) = work {
        val file = withContext(Dispatchers.IO) { CommunicationFiles.write(activity, content()) }
        exportFile = file
        if (share) CommunicationFiles.share(activity, file)
        else exporter.launch("communications-${java.time.LocalDate.now()}.md")
    }

    @Suppress(
        "TooGenericExceptionCaught"
    ) // External providers report varied exceptions; cancellation is never swallowed.
    private fun work(action: suspend () -> Unit) {
        activity.lifecycleScope.launch {
            mutex.lock()
            busy = true
            error = null
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error =
                    failure.message ?: "Не удалось выполнить действие. Исходные данные сохранены."
            } finally {
                busy = false
                mutex.unlock()
            }
        }
    }
}
