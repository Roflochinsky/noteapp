package com.roflochinsky.noteapp.pipeline

import java.io.IOException

/**
 * Порт GitHub-API (решение LLD-3): в тестах — фейк на картах в памяти, в приложении —
 * [GithubClient]. Срез Н1 дал чтение, Н2 — запись одного файла; `compare` придёт срезом Н7.
 */
interface GithubApi {
    /** SHA коммита, на который смотрит ветка по умолчанию. */
    @Throws(IOException::class) fun readRef(): String

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

/** Ответ записи: свежий blob-SHA файла (у удаления его нет) и SHA коммита (research §7.1). */
data class Written(val sha: String?, val commitSha: String)

/** Не-2xx от GitHub: код различает штатные ветки (409/422/403), тело — только для лога. */
class GithubHttpException(val code: Int, message: String) : IOException(message)
