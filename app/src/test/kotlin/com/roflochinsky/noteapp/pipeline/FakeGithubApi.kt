package com.roflochinsky.noteapp.pipeline

import java.io.IOException

/**
 * Фейк порта [GithubApi] на картах в памяти (решение LLD-3: фейк вместо моков HTTP). Blob-SHA — хэш
 * текста: меняется вместе с содержимым, как в git.
 */
class FakeGithubApi(
    private val files: MutableMap<String, String> = mutableMapOf(),
    var commitSha: String = "commit-1",
) : GithubApi {

    var readBlobCalls = 0
        private set

    /** Шире IOException: настоящий клиент разбирает ответ через org.json и кидает JSONException. */
    var fail: Exception? = null

    fun put(path: String, text: String) {
        files[path] = text
        commitSha = "commit-${files.hashCode()}"
    }

    fun remove(path: String) {
        files.remove(path)
        commitSha = "commit-${files.hashCode()}"
    }

    private fun sha(text: String) = "blob-${text.hashCode()}"

    override fun readRef(): String = fail?.let { throw it } ?: commitSha

    override fun readTree(commitSha: String): Map<String, String> =
        fail?.let { throw it } ?: files.mapValues { sha(it.value) }

    override fun readBlob(sha: String): String {
        fail?.let { throw it }
        readBlobCalls++
        return files.values.firstOrNull { sha(it) == sha } ?: throw IOException("нет блоба $sha")
    }

    override fun readFile(path: String): String =
        fail?.let { throw it } ?: files[path] ?: throw GithubHttpException(HTTP_NOT_FOUND, path)

    private companion object {
        const val HTTP_NOT_FOUND = 404
    }
}
