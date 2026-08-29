package com.roflochinsky.noteapp.pipeline

import java.io.IOException

/**
 * Фейк порта [GithubApi] на картах в памяти (решение LLD-3: фейк вместо моков HTTP). Blob-SHA — хэш
 * текста: меняется вместе с содержимым, как в git. Запись повторяет коды contents API: 409 на
 * устаревший sha, 422 на PUT без sha по занятому пути, 404 на удаление несуществующего.
 */
@Suppress("TooManyFunctions") // фейк порта повторяет его поверхность целиком
class FakeGithubApi(
    private val files: MutableMap<String, String> = mutableMapOf(),
    var commitSha: String = "commit-1",
) : GithubApi {

    var readBlobCalls = 0
        private set

    var readRefCalls = 0
        private set

    var writeCalls = 0
        private set

    /** Шире IOException: настоящий клиент разбирает ответ через org.json и кидает JSONException. */
    var fail: Exception? = null

    /** «Пока мы правили, в git приехало своё» — вызывается тестом перед ответом на запись. */
    var onWrite: (() -> Unit)? = null

    fun put(path: String, text: String) {
        files[path] = text
        commitSha = "commit-${files.hashCode()}"
    }

    fun remove(path: String) {
        files.remove(path)
        commitSha = "commit-${files.hashCode()}"
    }

    fun text(path: String): String? = files[path]

    fun paths(): Set<String> = files.keys.toSet()

    private fun sha(text: String) = "blob-${text.hashCode()}"

    override fun readRef(): String {
        fail?.let { throw it }
        readRefCalls++
        return commitSha
    }

    /**
     * Дерево спрашивают по SHA коммита — фейк на этом настаивает: подстановка имени ветки или
     * протухшего SHA здесь падает, а не «работает случайно» (долг Н-7 ревью Н1).
     */
    override fun readTree(commitSha: String): Map<String, String> {
        fail?.let { throw it }
        if (commitSha != this.commitSha) {
            throw GithubHttpException(HTTP_NOT_FOUND, "нет коммита $commitSha")
        }
        return files.mapValues { sha(it.value) }
    }

    override fun readBlob(sha: String): String {
        fail?.let { throw it }
        readBlobCalls++
        return files.values.firstOrNull { sha(it) == sha } ?: throw IOException("нет блоба $sha")
    }

    override fun readFile(path: String): RepoCache.Entry {
        fail?.let { throw it }
        val text = files[path] ?: throw GithubHttpException(HTTP_NOT_FOUND, path)
        return RepoCache.Entry(sha(text), text)
    }

    override fun putFile(path: String, content: String, message: String, sha: String?): Written {
        before()
        val current = files[path]
        if (sha == null && current != null) {
            throw GithubHttpException(HTTP_UNPROCESSABLE, "путь занят: $path")
        }
        if (sha != null && current == null) throw GithubHttpException(HTTP_NOT_FOUND, path)
        if (sha != null && sha != sha(current!!)) {
            throw GithubHttpException(HTTP_CONFLICT, "$path does not match $sha")
        }
        put(path, content)
        return Written(sha(content), commitSha)
    }

    override fun deleteFile(path: String, message: String, sha: String): Written {
        before()
        val current = files[path] ?: throw GithubHttpException(HTTP_NOT_FOUND, path)
        if (sha != sha(current)) throw GithubHttpException(HTTP_CONFLICT, "$path does not match")
        remove(path)
        return Written(null, commitSha)
    }

    private fun before() {
        fail?.let { throw it }
        writeCalls++
        onWrite?.invoke()
    }

    private companion object {
        const val HTTP_NOT_FOUND = 404
        const val HTTP_CONFLICT = 409
        const val HTTP_UNPROCESSABLE = 422
    }
}
