package com.roflochinsky.noteapp.pipeline

import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/**
 * Локальный кэш репо заметок: тексты, карта путь→blobSha и коммит, на котором мы синхронизированы
 * (решение LLD-13). Один json, запись через temp+rename. Битый файл, чужая версия, смена репо или
 * токена — холодный старт, а не исключение: кэш всегда восстановим из репо.
 *
 * Личность кэша (репо + токен) задаётся конструктором: снимок нельзя прочитать, не назвав, чей он —
 * иначе данные, прочитанные отозванным токеном, остаются на экране (решение LLD-23). Сам токен в
 * файл не пишется, только его отпечаток.
 */
class RepoCache(val dir: File, private val repo: String, token: String?) {

    private val tokenHash = fingerprint(token)

    data class Entry(val sha: String, val text: String)

    data class Snapshot(val commitSha: String = "", val files: Map<String, Entry> = emptyMap())

    private var memo: Snapshot? = null
    private var seen = ""

    /**
     * Снимок с диска, перечитанный только если файл изменился. Читать в конструктор один раз
     * нельзя: воркер записи отправил правку, убрал её из журнала, а экран остался со старым текстом
     * — галочка отскакивает (решение LLD-5).
     *
     * `@Synchronized` — потому что `memo` и `seen` пишет [save] с потока воркера, а читает экран:
     * без замка порядок этих двух присваиваний ничем не связан, и читатель может увидеть новую
     * метку при старом снимке, то есть застрять на устаревшем тексте.
     *
     * ponytail: метка — время и размер файла, не счётчик версий внутри json. Потолок: две записи в
     * одну миллисекунду одинаковой длины экран пропустит до следующего тика; если такое всплывёт —
     * в json кладётся счётчик и сравнивается он.
     */
    @Synchronized
    fun snapshot(): Snapshot {
        val now = stamp()
        val memo = memo
        if (memo != null && now == seen) return memo
        return load().also {
            this.memo = it
            seen = now
        }
    }

    /** Дешёвая метка состояния кэша: не изменилась — пересобирать список не из чего. */
    fun stamp(): String = file().let { "${it.lastModified()}:${it.length()}" }

    fun load(): Snapshot =
        runCatching {
                val json = JSONObject(file().readText())
                if (
                    json.getInt("version") != VERSION ||
                        json.getString("repo") != repo ||
                        json.optString("tokenHash") != tokenHash
                ) {
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

    @Synchronized
    fun save(snapshot: Snapshot) {
        val files = JSONObject()
        snapshot.files.forEach { (path, e) ->
            files.put(path, JSONObject().put("sha", e.sha).put("text", e.text))
        }
        val json =
            JSONObject()
                .put("version", VERSION)
                .put("repo", repo)
                .put("tokenHash", tokenHash)
                .put("commit", snapshot.commitSha)
                .put("files", files)
        dir.mkdirs()
        val tmp = File(dir, "$NAME.tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(file())) {
            file().writeText(tmp.readText())
            tmp.delete()
        }
        memo = snapshot
        seen = stamp()
    }

    private fun file() = File(dir, NAME)

    private companion object {
        const val VERSION = 1
        const val NAME = "repo.json"
        const val HEX = "%02x"

        /** Отпечаток токена: смену видно, сам токен по нему не восстановить. */
        fun fingerprint(token: String?): String =
            if (token.isNullOrEmpty()) {
                ""
            } else {
                MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") {
                    HEX.format(it)
                }
            }
    }
}
