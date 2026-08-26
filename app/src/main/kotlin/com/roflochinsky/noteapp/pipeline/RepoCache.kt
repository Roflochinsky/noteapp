package com.roflochinsky.noteapp.pipeline

import java.io.File
import org.json.JSONObject

/**
 * Локальный кэш репо заметок: тексты, карта путь→blobSha и коммит, на котором мы синхронизированы
 * (решение LLD-13). Один json, запись через temp+rename. Битый файл, чужая версия или смена репо —
 * холодный старт, а не исключение: кэш всегда восстановим из репо.
 */
class RepoCache(private val dir: File) {

    data class Entry(val sha: String, val text: String)

    data class Snapshot(val commitSha: String = "", val files: Map<String, Entry> = emptyMap())

    fun load(repo: String): Snapshot =
        runCatching {
                val json = JSONObject(file().readText())
                if (json.getInt("version") != VERSION || json.getString("repo") != repo) {
                    return Snapshot()
                }
                val files = json.getJSONObject("files")
                Snapshot(
                    commitSha = json.optString("commit"),
                    files =
                        files.keys().asSequence().associateWith { path ->
                            val e = files.getJSONObject(path)
                            Entry(e.getString("sha"), e.getString("text"))
                        },
                )
            }
            .getOrDefault(Snapshot())

    fun save(repo: String, snapshot: Snapshot) {
        val files = JSONObject()
        snapshot.files.forEach { (path, e) ->
            files.put(path, JSONObject().put("sha", e.sha).put("text", e.text))
        }
        val json =
            JSONObject()
                .put("version", VERSION)
                .put("repo", repo)
                .put("commit", snapshot.commitSha)
                .put("files", files)
        dir.mkdirs()
        val tmp = File(dir, "$NAME.tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(file())) {
            file().writeText(tmp.readText())
            tmp.delete()
        }
    }

    private fun file() = File(dir, NAME)

    private companion object {
        const val VERSION = 1
        const val NAME = "repo.json"
    }
}
