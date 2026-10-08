package com.roflochinsky.noteapp.pipeline

import java.io.IOException
import java.time.OffsetDateTime

/** Reconciles an uncertain PUT before creating again, including a note moved by the Action. */
object RawNoteUpload {
    private const val TRANSCRIPT = "## Транскрипт"
    private val CONFLICT_CODES = setOf(409, 422)

    fun upload(api: GithubApi, path: String, content: String): String {
        existing(api, path, content)?.let {
            return it
        }
        return try {
            api.putFile(path, content, "Заметка ${path.substringAfterLast('/')}", sha = null)
            path
        } catch (e: GithubHttpException) {
            if (e.code !in CONFLICT_CODES) throw e
            // Another attempt may have committed after our initial read.
            existing(api, path, content) ?: throw e
        }
    }

    private fun existing(api: GithubApi, path: String, content: String): String? {
        val expected = requireNotNull(NoteFile.parse(path, content))
        val recorded = requireNotNull(recorded(expected))
        val prefix = path.substringAfterLast('/').removeSuffix(".md")
        val candidates =
            api.readTree(api.readRef()).filterKeys {
                NoteRef.isNote(it) && it.substringAfterLast('/').startsWith(prefix)
            }
        for ((remotePath, sha) in candidates) {
            val remote = NoteFile.parse(remotePath, api.readBlob(sha))
            if (recorded(remote) != recorded) continue
            if (remote?.section(TRANSCRIPT)?.trim() != expected.section(TRANSCRIPT)?.trim()) {
                throw UploadConflictException("В GitHub уже есть эта запись с другим транскриптом")
            }
            return remotePath
        }
        if (path in candidates) {
            // Two recordings in a minute share the legacy inbox filename. Wait until the
            // Action moves the first one; never overwrite it or claim it is the second one.
            throw UploadPendingException("ожидаю обработки другой записи этой минуты в GitHub")
        }
        return null
    }

    private fun recorded(note: NoteFile.Note?) =
        note?.fields?.get("recorded")?.let {
            runCatching { OffsetDateTime.parse(it).toLocalDateTime() }.getOrNull()
        }
}

class UploadConflictException(message: String) : IOException(message)

class UploadPendingException(message: String) : IOException(message)
