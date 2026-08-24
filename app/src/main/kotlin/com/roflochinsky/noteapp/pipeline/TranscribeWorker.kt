package com.roflochinsky.noteapp.pipeline

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.roflochinsky.noteapp.Probe
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Расшифровка одной заметки. Контент ходит через файлы NotesStore, в Data — только noteId (лимит
 * Data 10KB, вердикт LLD-3 плана v1). Срез С3 добавит constraints и цепочку пуша.
 */
class TranscribeWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val noteId = inputData.getString(KEY_NOTE_ID) ?: return@withContext Result.failure()
            val dir = NotesStore.noteDir(applicationContext, noteId)
            val audio = File(dir, NotesStore.AUDIO)
            if (!audio.exists()) return@withContext Result.failure()
            val key = Settings.deepgramKey(applicationContext)
            if (key == null) {
                Log.w(Probe.LOG_TAG, "PROBE:STT_SKIP no_key note=$noteId")
                return@withContext Result.failure()
            }
            try {
                val json = DeepgramClient.transcribe(audio, key)
                File(dir, NotesStore.TRANSCRIPT_JSON).writeText(json)
                val md = TranscriptMapper.toMarkdown(TranscriptMapper.fromDeepgramJson(json))
                File(dir, NotesStore.TRANSCRIPT_MD).writeText(md)
                Log.i(Probe.LOG_TAG, "PROBE:STT_OK note=$noteId chars=${md.length}")
                Result.success()
            } catch (e: IOException) {
                Log.w(Probe.LOG_TAG, "PROBE:STT_RETRY note=$noteId ${e.message?.take(ERR_PREVIEW)}")
                Result.retry()
            }
        }

    companion object {
        const val KEY_NOTE_ID = "noteId"
        private const val ERR_PREVIEW = 200

        fun enqueue(context: Context, noteId: String) {
            WorkManager.getInstance(context)
                .enqueue(
                    OneTimeWorkRequestBuilder<TranscribeWorker>()
                        .setInputData(workDataOf(KEY_NOTE_ID to noteId))
                        .build()
                )
        }
    }
}
