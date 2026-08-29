package com.roflochinsky.noteapp.pipeline

import java.io.File

/** Что показать под шапкой одной строкой (без диалогов и тостов). */
enum class SyncStatus {
    OK,
    NO_TOKEN,
    OFFLINE,
    NO_ACCESS,
    RATE_LIMIT,
}

/**
 * Единственная точка входа UI к репо заметок: экран просит задачи и обновление, про кэш и HTTP не
 * знает ничего. В срезе Н1 — только чтение.
 *
 * ponytail: чтение полное (ref + tree + изменившиеся блобы) — репо личное и маленькое; ETag,
 * `compare` и дельта живут в срезе Н7, раньше они экономят то, чего никто не тратит.
 */
class RepoStore(
    private val repo: String,
    private val cache: RepoCache,
    private val api: GithubApi?,
) {

    private var snapshot = cache.load(repo)

    /** Задачи из кэша — рисуются мгновенно, без сети (решение LLD-12). */
    fun tasks(): List<TaskFile.Task> =
        snapshot.files
            .filterKeys { it.startsWith(TaskFile.DIR) && it.endsWith(".md") }
            .map { (path, entry) -> TaskFile.parse(path, entry.text) }

    fun refresh(): SyncStatus {
        val api = api ?: return SyncStatus.NO_TOKEN
        return try {
            val commit = api.readRef()
            val tree = api.readTree(commit)
            val files =
                tree
                    .filterKeys { it.startsWith(TaskFile.DIR) && it.endsWith(".md") }
                    .mapValues { (path, sha) ->
                        snapshot.files[path]?.takeIf { it.sha == sha }
                            ?: RepoCache.Entry(sha, api.readBlob(sha))
                    }
            snapshot = RepoCache.Snapshot(commit, files)
            cache.save(repo, snapshot)
            SyncStatus.OK
        } catch (e: GithubHttpException) {
            status(e)
        } catch (@Suppress("SwallowedException", "TooGenericExceptionCaught") e: Exception) {
            // Ловим шире IOException намеренно: ответ разбирается через org.json, а JSONException
            // ему не родня. Ответ 200 с не-JSON телом (кэптив-портал, HTML от CDN) ронял корутину
            // экрана целиком — для владельца это неотличимо от «нет сети», ей и показываем.
            SyncStatus.OFFLINE
        }
    }

    private fun status(e: GithubHttpException): SyncStatus =
        when (e.code) {
            HTTP_UNAUTHORIZED,
            HTTP_NOT_FOUND -> SyncStatus.NO_ACCESS
            HTTP_FORBIDDEN,
            HTTP_TOO_MANY -> SyncStatus.RATE_LIMIT
            else -> SyncStatus.OFFLINE
        }

    companion object {
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_TOO_MANY = 429

        /** Кэш репо на телефоне — `files/repo/`. */
        fun cacheDir(filesDir: File): File = File(filesDir, "repo")
    }
}
