package com.roflochinsky.noteapp.pipeline

import android.content.Context
import java.io.File

/**
 * Хранилище заметок: files/notes/<id>/ (id = ГГГГММДД-ЧЧММСС). Статус заметки — наличие файлов,
 * отдельного state-файла нет (вердикт LLD-4 плана v1).
 */
object NotesStore {
    const val AUDIO = "audio.m4a"
    const val TRANSCRIPT_JSON = "transcript.json"
    const val TRANSCRIPT_MD = "transcript.md"
    const val MARKS = "marks.txt"
    const val PUSHED = "pushed.txt"
    const val DURATION = "duration.txt"

    /**
     * Почему заметка ещё не расшифрована, словами владельца: первая строка — для ленты, вторая —
     * начало ответа вендора для деталки. Файла нет — причины нет, и лента говорит про очередь.
     *
     * Файл живёт рядом с записью на телефоне и в репо заметок уезжать не должен: владельцу там
     * нужна заметка, а не служебная строка телефона. **Сегодня это держится устройством
     * `PushWorker.doWork` (он набирает `RawNote.Input` из одного `transcript.md`), а не тестом** —
     * собрать `CoroutineWorker` в юните нечем, `androidx.work:work-testing` в зависимостях нет.
     * Страж появится вместе с выносом набора `Input` в `PushWorker.inputFor` — объявленный долг 3
     * среза `nikitatrubaev-u4g.2`.
     */
    const val STATUS = "status.txt"

    private const val TMP = ".tmp"

    fun root(context: Context): File = File(context.filesDir, "notes").apply { mkdirs() }

    fun noteDir(context: Context, id: String): File = File(root(context), id).apply { mkdirs() }

    fun list(context: Context): List<Note> =
        root(context)
            .listFiles { f -> f.isDirectory }
            .orEmpty()
            .sortedByDescending { it.name }
            .map { dir ->
                val md = File(dir, TRANSCRIPT_MD)
                val lines = if (md.exists()) md.readLines() else emptyList()
                Note(
                    id = dir.name,
                    hasAudio = File(dir, AUDIO).exists(),
                    transcribed = md.exists(),
                    pushed = File(dir, PUSHED).exists(),
                    durationSec = durationSec(dir),
                    title = lines.firstOrNull()?.let(::stripCue)?.take(TITLE_MAX) ?: "",
                    preview = lines.take(2).joinToString("\n"),
                    status = File(dir, STATUS).takeIf(File::exists)?.readText().orEmpty(),
                )
            }

    /**
     * Файл заметки пишется целиком или не пишется вовсе: текст ложится в `<имя>.tmp` рядом и
     * переезжает на место одним `renameTo` — так же делают [RepoCache] и [WriteQueue].
     *
     * Цена прямой записи тихая и дорогая: убитый посреди `writeText` процесс оставляет обрезанный
     * `transcript.md`, а он навсегда выключает перераспознавание ([TranscribeWorker] при
     * существующем файле выходит `success`) и молча уезжает в репо заметок куском разговора.
     *
     * Фолбэк на прямую запись — на случай, когда `renameTo` не удался (образец `RepoCache`): это
     * хуже атомарного переезда, но лучше потерянного файла.
     */
    fun writeAtomic(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + TMP)
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    /**
     * Причина снята: файла нет — причины нет ([STATUS]). Каталогом записи заведует хранилище,
     * поэтому оба стирателя ходят сюда, а не в `File(...).delete()` по месту: причину снимает
     * [TranscribeWorker] на исходе попытки и `MainActivity.enqueueWaiting` в момент ввода ключа.
     *
     * @return лежала ли причина до вызова. По этому ответу `enqueueWaiting` и отличает запись,
     *   которая встала с известной бедой, от той, что просто идёт (у идущей заливки причины нет).
     */
    fun clearStatus(dir: File): Boolean = File(dir, STATUS).delete()

    /** duration.txt, а для старых заметок — ленивая миграция из метаданных аудио. */
    private fun durationSec(dir: File): Long {
        val f = File(dir, DURATION)
        val cached = f.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull()
        val audio = File(dir, AUDIO)
        return when {
            cached != null -> cached
            !audio.exists() -> 0
            else -> {
                val sec =
                    runCatching {
                            android.media.MediaMetadataRetriever().use { r ->
                                r.setDataSource(audio.absolutePath)
                                (r.extractMetadata(
                                        android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
                                    )
                                    ?.toLongOrNull() ?: 0L) / MS_IN_SEC
                            }
                        }
                        .getOrDefault(0L)
                if (sec > 0) runCatching { f.writeText(sec.toString()) }
                sec
            }
        }
    }

    private const val MS_IN_SEC = 1000L

    /** "[00:12] Спикер 1: текст" → "текст" */
    private fun stripCue(line: String): String = line.substringAfter(": ", line)

    private const val TITLE_MAX = 48

    data class Note(
        val id: String,
        val hasAudio: Boolean,
        val transcribed: Boolean,
        val pushed: Boolean,
        val durationSec: Long = 0,
        val title: String = "",
        val preview: String = "",
        /** Содержимое [STATUS]; пусто — причина неизвестна и заметка просто ждёт очереди. */
        val status: String = "",
    )
}
