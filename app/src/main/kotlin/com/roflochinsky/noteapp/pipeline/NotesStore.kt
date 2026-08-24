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

    fun root(context: Context): File = File(context.filesDir, "notes").apply { mkdirs() }

    fun noteDir(context: Context, id: String): File = File(root(context), id).apply { mkdirs() }

    fun list(context: Context): List<Note> =
        root(context)
            .listFiles { f -> f.isDirectory }
            .orEmpty()
            .sortedByDescending { it.name }
            .map { dir ->
                val md = File(dir, TRANSCRIPT_MD)
                Note(
                    id = dir.name,
                    hasAudio = File(dir, AUDIO).exists(),
                    transcribed = md.exists(),
                    pushed = File(dir, PUSHED).exists(),
                    preview =
                        if (md.exists()) md.readText().lineSequence().take(2).joinToString("\n")
                        else "",
                )
            }

    data class Note(
        val id: String,
        val hasAudio: Boolean,
        val transcribed: Boolean,
        val pushed: Boolean,
        val preview: String,
    )
}
