package com.roflochinsky.noteapp.pipeline

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.roflochinsky.noteapp.Probe
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Единственный воркер, который пишет в репо задачи (решение LLD-6): своя уникальная цепочка
 * `repo-write` с `APPEND_OR_REPLACE` — KEEP проглотил бы вторую правку, а APPEND порвал бы цепочку.
 *
 * Цепочка заметок (`note-*`, [PipelineQueue]) отдельная и приоритетная: правки задач никогда не
 * задерживают доставку свежей записи (принцип 2).
 */
class RepoWriteWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val repo = Settings.githubRepo(applicationContext)
            val token = Settings.githubToken(applicationContext)
            if (token == null) {
                Log.w(Probe.LOG_TAG, "PROBE:WRITE_SKIP no_token")
                return@withContext Result.retry()
            }
            val store =
                RepoStore(
                    cache = RepoCache(RepoStore.cacheDir(applicationContext.filesDir), repo, token),
                    api = GithubClient(repo, token),
                )
            var sent = 0
            var push = store.push()
            while (push == RepoStore.Push.MORE) {
                sent++
                delay(PAUSE_MS) // ≥1 с между мутациями — требование доков (research §3.3)
                push = store.push()
            }
            val outcome =
                when (push) {
                    RepoStore.Push.EMPTY -> Result.success()
                    RepoStore.Push.RETRY -> Result.retry()
                    else -> Result.failure()
                }
            Log.i(Probe.LOG_TAG, "PROBE:WRITE_DONE sent=$sent ${outcome::class.simpleName}")
            outcome
        }

    companion object {
        private const val CHAIN = "repo-write"
        private const val PAUSE_MS = 1000L
        private const val BACKOFF_SEC = 30L

        /** Ставит drain в очередь: без сети цепочка ждёт и стартует сама, когда сеть появится. */
        fun schedule(context: Context) {
            val request =
                OneTimeWorkRequestBuilder<RepoWriteWorker>()
                    .setConstraints(
                        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                    )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SEC, TimeUnit.SECONDS)
                    .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(CHAIN, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
