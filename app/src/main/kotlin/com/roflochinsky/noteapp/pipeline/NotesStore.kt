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
                )
            }

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
    )
}
