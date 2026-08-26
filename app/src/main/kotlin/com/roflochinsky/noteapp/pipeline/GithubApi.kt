package com.roflochinsky.noteapp.pipeline

import java.io.IOException

/**
 * Порт GitHub-API (решение LLD-3): в тестах — фейк на картах в памяти, в приложении —
 * [GithubClient]. В срезе Н1 нужно только чтение; запись и `compare` придут срезами Н2 и Н7.
 */
interface GithubApi {
    /** SHA коммита, на который смотрит ветка по умолчанию. */
    @Throws(IOException::class) fun readRef(): String

    /** Карта `путь → blobSha` всего репо одним запросом (research §2.2). */
    @Throws(IOException::class) fun readTree(commitSha: String): Map<String, String>

    @Throws(IOException::class) fun readBlob(sha: String): String

    @Throws(IOException::class) fun readFile(path: String): String
}

/** Не-2xx от GitHub: код различает штатные ветки (409/422/403), тело — только для лога. */
class GithubHttpException(val code: Int, message: String) : IOException(message)
