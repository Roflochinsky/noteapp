package com.roflochinsky.noteapp.pipeline

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.roflochinsky.noteapp.Probe
import java.io.File
import java.io.IOException
import java.time.OffsetDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Пуш raw-заметки в inbox/ репо заметок. Контент через файлы NotesStore (LLD-3). */
class PushWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val noteId = inputData.getString(KEY_NOTE_ID) ?: return@withContext Result.failure()
            val dir = NotesStore.noteDir(applicationContext, noteId)
            val transcript = File(dir, NotesStore.TRANSCRIPT_MD)
            if (!transcript.exists()) return@withContext Result.failure()
            if (File(dir, NotesStore.PUSHED).exists()) return@withContext Result.success()
            val token = Settings.githubToken(applicationContext)
            if (token == null) {
                Log.w(Probe.LOG_TAG, "PROBE:PUSH_SKIP no_token note=$noteId")
                return@withContext Result.failure()
            }
            val marks =
                File(dir, NotesStore.MARKS)
                    .takeIf { it.exists() }
                    ?.readLines()
                    ?.mapNotNull { it.trim().toLongOrNull() }
                    .orEmpty()
            val md =
                RawNote.build(
                    RawNote.Input(
                        noteId = noteId,
                        zone = OffsetDateTime.now().offset,
                        durationSec = audioDurationSec(File(dir, NotesStore.AUDIO)),
                        device = Build.MODEL,
                        marksMs = marks,
                        transcriptMd = transcript.readText().trimEnd(),
                    )
                )
            val path = "inbox/${RawNote.fileName(noteId)}"
            try {
                GithubClient(Settings.githubRepo(applicationContext), token)
                    .putFile(
                        path = path,
                        content = md,
                        message = "Заметка ${RawNote.fileName(noteId)}",
                    )
                File(dir, NotesStore.PUSHED).writeText(path)
                Log.i(Probe.LOG_TAG, "PROBE:PUSH_OK note=$noteId path=$path")
                Result.success()
            } catch (e: IOException) {
                Log.w(
                    Probe.LOG_TAG,
                    "PROBE:PUSH_RETRY note=$noteId ${e.message?.take(ERR_PREVIEW)}",
                )
                Result.retry()
            }
        }

    private fun audioDurationSec(audio: File): Long {
        if (!audio.exists()) return 0
        return runCatching {
                MediaMetadataRetriever().use { r ->
                    r.setDataSource(audio.absolutePath)
                    val ms =
                        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                            ?.toLongOrNull() ?: 0L
                    ms / MS_IN_SEC
                }
            }
            .getOrDefault(0L)
    }

    companion object {
        const val KEY_NOTE_ID = "noteId"
        private const val ERR_PREVIEW = 200
        private const val MS_IN_SEC = 1000L
    }
}
