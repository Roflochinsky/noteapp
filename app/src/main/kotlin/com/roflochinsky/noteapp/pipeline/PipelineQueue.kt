package com.roflochinsky.noteapp.pipeline

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Очередь пайплайна: транскрипт → пуш, уникальная цепочка на заметку. Constraint NETWORK_CONNECTED
 * — без сети цепочка лежит в очереди и стартует сама при появлении сети (срез С3).
 */
object PipelineQueue {

    /**
     * Префикс имени цепочки заметки. Цепочка задач (`RepoWriteWorker.CHAIN`) обязана называться
     * иначе: WorkManager сериализует работу внутри одного уникального имени, и совпади они — правка
     * задачи задержала бы доставку свежей записи (принцип 2). Сторожит `WorkChainsTest`.
     */
    internal const val NOTE_PREFIX = "note-"

    private const val BACKOFF_SEC = 30L

    fun enqueue(context: Context, noteId: String) {
        val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val transcribe =
            OneTimeWorkRequestBuilder<TranscribeWorker>()
                .setInputData(workDataOf(TranscribeWorker.KEY_NOTE_ID to noteId))
                .setConstraints(net)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SEC, TimeUnit.SECONDS)
                .build()
        val push =
            OneTimeWorkRequestBuilder<PushWorker>()
                .setInputData(workDataOf(PushWorker.KEY_NOTE_ID to noteId))
                .setConstraints(net)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SEC, TimeUnit.SECONDS)
                .build()
        WorkManager.getInstance(context)
            .beginUniqueWork(NOTE_PREFIX + noteId, ExistingWorkPolicy.KEEP, transcribe)
            .then(push)
            .enqueue()
    }
}
