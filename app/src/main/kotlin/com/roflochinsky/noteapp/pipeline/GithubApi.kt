package com.roflochinsky.noteapp.pipeline

import java.io.IOException

/**
 * Порт GitHub-API (решение LLD-3): в тестах — фейк на картах в памяти, в приложении —
 * [GithubClient]. Срез Н1 дал чтение, Н2 — запись одного файла, Н7 — дешёвое обновление (условный
 * `readRef` и `compare`).
 */
interface GithubApi {
    /**
     * SHA коммита, на который смотрит ветка по умолчанию. Безусловно, то есть платно: путь для тех,
     * кому ETag хранить негде (`findDonePath`, разовая миграция).
     */
    @Throws(IOException::class)
    fun readRef(): String = checkNotNull(readRef(null)) { "безусловный запрос 304 не отдаёт" }.sha

    /**
     * Условный опрос ветки с `If-None-Match` (research §3.2): пока ветка не двигалась, GitHub
     * отвечает `304`, и **такой ответ не тратит квоту вообще** — на этом стоит весь дешёвый
     * поллинг. `null` в ответе и значит «304, изменений нет». Пустой [etag] делает запрос
     * безусловным.
     */
    @Throws(IOException::class) fun readRef(etag: String?): Ref?

    /**
     * Что изменилось между двумя коммитами — один запрос, дающий и пути, и свежие blob-SHA
     * (research §6.C). Разбор ответа — в чистом [RepoDelta].
     */
    @Throws(IOException::class) fun compare(base: String, head: String): RepoDelta

    /** Карта `путь → blobSha` всего репо одним запросом (research §2.2). */
    @Throws(IOException::class) fun readTree(commitSha: String): Map<String, String>

    @Throws(IOException::class) fun readBlob(sha: String): String

    /** Текст и свежий blob-SHA одним запросом — то, что нужно на 409 (research §7.4). */
    @Throws(IOException::class) fun readFile(path: String): RepoCache.Entry

    /**
     * `sha` обязателен при обновлении и запрещён при создании: PUT без sha по существующему пути
     * даёт 422, а не воскресший дубль (решение LLD-8).
     */
    @Throws(IOException::class)
    fun putFile(path: String, content: String, message: String, sha: String?): Written

    @Throws(IOException::class) fun deleteFile(path: String, message: String, sha: String): Written

    /**
     * Пачка правок **одним** коммитом через git data API (research §7.2): дерево поверх `base_tree`
     * → коммит → `PATCH ref` с `force: false`. Применяется целиком или не применяется вовсе — ровно
     * это свойство нужно миграции («один коммит, откатываемый одним `revert`») и удалению заметки
     * вместе с её задачами. Тела запросов собирает чистый [BatchPlan].
     *
     * Пустая пачка коммита не делает и в сеть не ходит: `commitSha` в ответе пустой.
     */
    @Throws(IOException::class)
    fun commitBatch(changes: List<BatchPlan.Change>, message: String): Written
}

/**
 * Ищет обработанный файл по префиксу имени (Action добавил слаг) — контракт HLD-1 v1.
 *
 * Живёт над портом, а не в адаптере: дерево читается по SHA коммита, как объявлено в [readTree]
 * (раньше сюда подставлялось имя ветки — работало случайно и фейком не проверялось).
 */
@Throws(IOException::class)
fun GithubApi.findDonePath(fileBase: String): String? =
    readTree(readRef()).keys.firstOrNull {
        !it.startsWith("inbox/") && it.substringAfterLast('/').startsWith(fileBase)
    }

/**
 * Ветка: где она сейчас и чем спрашивать в следующий раз.
 *
 * ETag кэшируется вместе с личностью кэша (репо + отпечаток токена, [RepoCache]) — он зависит от
 * заголовков запроса: на одном URL без токена приходит слабый, с токеном сильный (research §3.2).
 * Чужой ETag молча ломает поллинг, поэтому смена токена уносит его вместе со снимком.
 */
data class Ref(val sha: String, val etag: String?)

/** Ответ записи: свежий blob-SHA файла (у удаления его нет) и SHA коммита (research §7.1). */
data class Written(val sha: String?, val commitSha: String)

/** Не-2xx от GitHub: код различает штатные ветки (409/422/403), тело — только для лога. */
class GithubHttpException(val code: Int, message: String) : IOException(message)
