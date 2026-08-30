package com.roflochinsky.noteapp.pipeline

import java.io.IOException

/**
 * Фейк порта [GithubApi] на картах в памяти (решение LLD-3: фейк вместо моков HTTP). Blob-SHA — хэш
 * текста: меняется вместе с содержимым, как в git. Запись повторяет коды contents API: 409 на
 * устаревший sha, 422 на PUT без sha по занятому пути, 404 на удаление несуществующего.
 */
class FakeGithubApi(
    private val files: MutableMap<String, String> = mutableMapOf(),
    var commitSha: String = "commit-1",
) : GithubApi {

    var readBlobCalls = 0
        private set

    var readRefCalls = 0
        private set

    /** Сколько условных опросов закончились 304 — то есть ничего не стоили (research §3.2). */
    var notModifiedCalls = 0
        private set

    var compareCalls = 0
        private set

    var readTreeCalls = 0
        private set

    /**
     * «`compare` картины не даёт»: усечение или разошедшиеся ветки — тогда пересбор через дерево.
     */
    var compareStale = false

    var writeCalls = 0
        private set

    /** Шире IOException: настоящий клиент разбирает ответ через org.json и кидает JSONException. */
    var fail: Exception? = null

    /** «Пока мы правили, в git приехало своё» — вызывается тестом перед ответом на запись. */
    var onWrite: (() -> Unit)? = null

    /**
     * «Пока обновление шло по сети, кто-то успел записать» — зовётся после того, как ответ на
     * чтение состояния репо собран, но снимок ещё не записан. Окно одно и то же у обоих путей
     * обновления, поэтому хук висит и на дереве, и на `compare`.
     */
    var onTree: (() -> Unit)? = null

    /** Блобы в git не исчезают, когда файл переписан: старый sha читается и после коммита. */
    private val blobs = mutableMapOf<String, String>()

    /** Карта репо на каждом коммите: `compare` без истории невозможен. */
    private val history = mutableMapOf<String, Map<String, String>>()

    init {
        history[commitSha] = files.mapValues { sha(it.value) }
    }

    fun put(path: String, text: String) {
        files[path] = text
        blobs[sha(text)] = text
        commit()
    }

    fun remove(path: String) {
        files.remove(path)
        commit()
    }

    fun text(path: String): String? = files[path]

    fun paths(): Set<String> = files.keys.toSet()

    private fun sha(text: String) = "blob-${text.hashCode()}"

    /** Коммит двигает ветку и запоминает карту репо — с неё потом отвечает [compare]. */
    private fun commit() {
        commitSha = "commit-${files.hashCode()}"
        history[commitSha] = files.mapValues { sha(it.value) }
    }

    /**
     * ETag фейка — сам SHA коммита: у настоящего `git/ref` тело и есть «где ветка», поэтому пока
     * ветка не двигалась, не меняется и он. Совпал с присланным — 304 и `null`, как у GitHub.
     */
    override fun readRef(etag: String?): Ref? {
        fail?.let { throw it }
        readRefCalls++
        if (etag == commitSha) {
            notModifiedCalls++
            return null
        }
        return Ref(commitSha, commitSha)
    }

    /**
     * Разница двух снимков репо по карте `путь → blobSha`. История коммитов ведётся в [history] —
     * без неё «что изменилось с прошлого раза» нечем ответить, а именно это и проверяют тесты
     * дельты.
     */
    override fun compare(base: String, head: String): RepoDelta {
        fail?.let { throw it }
        compareCalls++
        val was = history[base]
        val now = history[head]
        if (was == null || now == null || compareStale) return stale()
        val delta =
            RepoDelta(
                changed = now.filter { (path, sha) -> was[path] != sha },
                removed = was.keys - now.keys,
                stale = false,
            )
        onTree?.invoke()
        return delta
    }

    private fun stale() = RepoDelta(emptyMap(), emptySet(), stale = true)

    /**
     * Дерево спрашивают по SHA коммита — фейк на этом настаивает: подстановка имени ветки или
     * протухшего SHA здесь падает, а не «работает случайно» (долг Н-7 ревью Н1).
     */
    override fun readTree(commitSha: String): Map<String, String> {
        fail?.let { throw it }
        readTreeCalls++
        if (commitSha != this.commitSha) {
            throw GithubHttpException(HTTP_NOT_FOUND, "нет коммита $commitSha")
        }
        // Дерево снимается ДО хука: у настоящего запроса ответ тоже собран на момент коммита, а
        // не на момент, когда его дочитал клиент.
        val tree = files.mapValues { sha(it.value) }
        onTree?.invoke()
        return tree
    }

    override fun readBlob(sha: String): String {
        fail?.let { throw it }
        readBlobCalls++
        return blobs[sha] ?: throw IOException("нет блоба $sha")
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

    /**
     * Пачка применяется целиком и двигает коммит один раз — как настоящий git data API. Хук
     * [onWrite] здесь тоже срабатывает: батч — такая же мутация, и «пока мы собирали пачку, в git
     * приехало своё» проверяется тем же способом.
     */
    override fun commitBatch(changes: List<BatchPlan.Change>, message: String): Written {
        if (changes.isEmpty()) return Written(null, "")
        before()
        changes.forEach { change ->
            when (change) {
                is BatchPlan.Put -> files[change.path] = change.content
                is BatchPlan.Delete -> files.remove(change.path)
            }
        }
        changes.filterIsInstance<BatchPlan.Put>().forEach { blobs[sha(it.content)] = it.content }
        commit()
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
