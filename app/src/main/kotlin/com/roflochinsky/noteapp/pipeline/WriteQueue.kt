package com.roflochinsky.noteapp.pipeline

import java.io.File
import java.time.LocalDate
import org.json.JSONObject

/**
 * Журнал очереди записи в файлах `files/repo/queue/` (решение LLD-6): по файлу на операцию, имя
 * задаёт порядок. WorkManager Data сюда не годится — 10 КБ на всё и потеря операции при перезапуске
 * цепочки; файл переживает убитый процесс.
 *
 * Склейка — по паре «путь + цель правки»: два тапа по одному чекбоксу дают одну операцию
 * (побеждает последняя), а правка соседнего поля живёт своей.
 */
class WriteQueue(private val dir: File) {

    data class Op(
        val id: String,
        val path: String,
        val edit: Edit,
        /** SHA текста, на котором владелец правил, — база трёхстороннего слияния. */
        val baseSha: String?,
        val attempt: Int = 0,
    )

    /** Операции в порядке появления; битые файлы пропускаются, а не роняют очередь. */
    fun pending(): List<Op> =
        files().mapNotNull { file -> runCatching { read(file) }.getOrNull() }.toList()

    fun pending(path: String): List<Op> = pending().filter { it.path == path }

    fun enqueue(path: String, edit: Edit, baseSha: String?): Op {
        val now = pending()
        if (edit is Edit.DeleteFile) now.filter { it.path == path }.forEach { drop(it.id) }
        val same = now.firstOrNull { it.path == path && it.edit.target == edit.target }
        val op = Op(same?.id ?: nextId(), path, edit, baseSha)
        write(op)
        return op
    }

    /** Отмена снекбаром: операция ещё не ушла — просто убираем её из журнала. */
    fun cancel(id: String) = drop(id)

    fun done(op: Op) = drop(op.id)

    fun retry(op: Op): Op = op.copy(attempt = op.attempt + 1).also { write(it) }

    private fun files(): Sequence<File> =
        dir.listFiles().orEmpty().filter { it.name.endsWith(EXT) }.sortedBy { it.name }.asSequence()

    private fun drop(id: String) {
        File(dir, "$id$EXT").delete()
    }

    private fun nextId(): String {
        val last =
            files().mapNotNull { it.name.takeWhile(Char::isDigit).toIntOrNull() }.maxOrNull() ?: 0
        return (last + 1).toString().padStart(WIDTH, '0')
    }

    private fun write(op: Op) {
        dir.mkdirs()
        val json =
            JSONObject()
                .put("path", op.path)
                .put("baseSha", op.baseSha ?: JSONObject.NULL)
                .put("attempt", op.attempt)
                .put("edit", encode(op.edit))
        val tmp = File(dir, "${op.id}$EXT.tmp")
        tmp.writeText(json.toString())
        val target = File(dir, "${op.id}$EXT")
        if (!tmp.renameTo(target)) {
            target.writeText(tmp.readText())
            tmp.delete()
        }
    }

    private fun read(file: File): Op {
        val json = JSONObject(file.readText())
        return Op(
            id = file.name.removeSuffix(EXT),
            path = json.getString("path"),
            edit = decode(json.getJSONObject("edit")),
            baseSha = json.optString("baseSha").takeIf { it.isNotEmpty() },
            attempt = json.optInt("attempt"),
        )
    }

    private companion object {
        const val EXT = ".json"
        const val WIDTH = 6
        const val TYPE = "t"

        fun encode(edit: Edit): JSONObject {
            val json = JSONObject().put(TYPE, edit::class.simpleName)
            return when (edit) {
                is Edit.SetField -> json.put("key", edit.key).put("value", edit.value ?: NULL)
                is Edit.SetTitle -> json.put("title", edit.title)
                is Edit.SetStatus ->
                    json.put("status", edit.status).put("done", edit.done?.toString() ?: NULL)
                is Edit.ToggleSubtask -> json.put("text", edit.text).put("done", edit.done)
                is Edit.AddSubtask -> json.put("text", edit.text)
                is Edit.CreateTask -> json.put("content", edit.content)
                Edit.DeleteFile -> json
            }
        }

        fun decode(json: JSONObject): Edit =
            when (val type = json.getString(TYPE)) {
                "SetField" -> Edit.SetField(json.getString("key"), json.str("value"))
                "SetTitle" -> Edit.SetTitle(json.getString("title"))
                "SetStatus" ->
                    Edit.SetStatus(
                        json.getString("status"),
                        json.str("done")?.let(LocalDate::parse),
                    )
                "ToggleSubtask" ->
                    Edit.ToggleSubtask(json.getString("text"), json.getBoolean("done"))
                "AddSubtask" -> Edit.AddSubtask(json.getString("text"))
                "CreateTask" -> Edit.CreateTask(json.getString("content"))
                "DeleteFile" -> Edit.DeleteFile
                else -> error("неизвестная операция очереди: $type")
            }

        val NULL: Any = JSONObject.NULL

        fun JSONObject.str(key: String): String? = if (isNull(key)) null else getString(key)
    }
}
