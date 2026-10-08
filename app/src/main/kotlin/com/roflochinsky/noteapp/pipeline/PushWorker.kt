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
import org.json.JSONException

/** Пуш raw-заметки в inbox/ репо заметок. Контент через файлы NotesStore (LLD-3). */
class PushWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            try {
                push()
            } catch (_: IOException) {
                // Includes local status/marker writes and failures inside an error handler.
                Result.retry()
            } catch (_: JSONException) {
                // A response can be truncated even after GitHub accepted the write.
                Result.retry()
            }
        }

    private fun push(): Result {
        val noteId = inputData.getString(KEY_NOTE_ID) ?: return Result.failure()
        val dir = NotesStore.noteDir(applicationContext, noteId)
        val transcript = File(dir, NotesStore.TRANSCRIPT_MD)
        val token = Settings.githubToken(applicationContext)
        return when {
            !transcript.exists() -> Result.failure()
            NotesStore.pushedPath(dir) != null -> Result.success()
            token == null -> {
                Log.w(Probe.LOG_TAG, "PROBE:PUSH_SKIP no_token note=$noteId")
                NotesStore.writeAtomic(File(dir, NotesStore.STATUS), "нет GitHub-токена")
                Result.retry()
            }
            else -> upload(dir, noteId, token, transcript)
        }
    }

    private fun upload(dir: File, noteId: String, token: String, transcript: File): Result {
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
        return try {
            val remotePath =
                RawNoteUpload.upload(
                    GithubClient(Settings.githubRepo(applicationContext), token),
                    path,
                    md,
                )
            NotesStore.writeAtomic(File(dir, NotesStore.PUSHED), remotePath)
            NotesStore.clearStatus(dir)
            Log.i(Probe.LOG_TAG, "PROBE:PUSH_OK note=$noteId path=$path")
            Result.success()
        } catch (e: UploadConflictException) {
            NotesStore.writeAtomic(File(dir, NotesStore.STATUS), e.message.orEmpty())
            Result.failure()
        } catch (e: UploadPendingException) {
            NotesStore.writeAtomic(File(dir, NotesStore.STATUS), e.message.orEmpty())
            Result.retry()
        } catch (e: GithubHttpException) {
            NotesStore.writeAtomic(File(dir, NotesStore.STATUS), "ошибка GitHub ${e.code}")
            if (e.code in FATAL_HTTP) Result.failure() else Result.retry()
        } catch (e: IOException) {
            Log.w(Probe.LOG_TAG, "PROBE:PUSH_RETRY note=$noteId ${e.message?.take(ERR_PREVIEW)}")
            NotesStore.writeAtomic(File(dir, NotesStore.STATUS), "ожидаю соединения с GitHub")
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
        private val FATAL_HTTP = setOf(400, 401, 404)
    }
}
