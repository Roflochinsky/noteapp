package com.roflochinsky.noteapp.pipeline

import java.io.File
import java.time.LocalDate
import org.json.JSONObject

/**
 * Журнал очереди записи в файлах `files/repo/queue/` (решение LLD-6): по файлу на операцию, имя
 * задаёт порядок. WorkManager Data сюда не годится — 10 КБ на всё и потеря операции при перезапуске
 * цепочки; файл переживает убитый процесс.
 *
 * Базы слияния операция не несёт: ею служит запись кэша по этому пути (уточнение LLD-1 от
 * 2026-08-29, обоснование в плане). `baseSha` из файлов старого формата просто игнорируется.
 *
 * Склейка — по паре «путь + цель правки»: два тапа по одному чекбоксу дают одну операцию (побеждает
 * последняя), а правка соседнего поля живёт своей.
 *
 * Журнал трогают двое: главный поток (правка владельца) и воркер записи (снятие отправленного). У
 * журнала поэтому свой монитор — на все публичные методы. Он держится микросекунды: тут только
 * файлы, сети нет. Монитор [RepoStore] сюда не годится: [RepoStore.push] держит его через сеть, и
 * правка владельца ждала бы до минуты.
 */
class WriteQueue(private val dir: File) {

    data class Op(val id: String, val path: String, val edit: Edit, val attempt: Int = 0)

    /** Операции в порядке появления; битые файлы пропускаются, а не роняют очередь. */
    @Synchronized
    fun pending(): List<Op> =
        files().mapNotNull { file -> runCatching { read(file) }.getOrNull() }.toList()

    @Synchronized
    fun enqueue(path: String, edit: Edit): Op {
        val now = pending()
        if (edit is Edit.DeleteFile)
            now.filter { it.path == path }.forEach { File(dir, "${it.id}$EXT").delete() }
        val same = now.firstOrNull { it.path == path && it.edit.target == edit.target }
        val op = Op(same?.id ?: nextId(), path, edit)
        write(op)
        return op
    }

    /**
     * Отмена снекбаром: операция ещё лежит в журнале — просто убираем её. `false` значит «снимать
     * нечего»: в журнале этого id уже нет, то есть отправка его унесла. Сверка по содержимому, как
     * в [done], тут не при чём — операция не менялась, она просто уже не наша. Что сказать
     * владельцу, решает [RepoStore.cancel].
     */
    @Synchronized fun cancel(id: String): Boolean = File(dir, "$id$EXT").delete()

    /**
     * Снятие отправленной операции — условное, по содержимому, а не по id. Пока PUT был в полёте,
     * владелец мог поправить то же поле: склейка кладёт новую правку В ТОТ ЖЕ файл журнала, и
     * удаление по id снесло бы её вместе с отправленной (блокер Б1). Изменилась — операция остаётся
     * и уедет следующим заходом; отправленную правку к тому моменту уже держит кэш.
     *
     * ponytail: сверка обычным `equals` прочитанной операции, без версий и отпечатков — `Op` и все
     * `Edit` data-классы. Расширять замок `push` нельзя: он держит монитор через сеть (до трёх
     * запросов по 60 секунд), и правка владельца ждала бы минуту.
     */
    @Synchronized
    fun done(op: Op) {
        if (unchanged(op)) File(dir, "${op.id}$EXT").delete()
    }

    /**
     * Повтор после 409 — тоже условный: если владелец успел склеить свою правку в тот же файл,
     * переписывать её старой операцией нельзя. Свежая правка ляжет поверх чужого текста, который
     * ветка 409 уже положила в кэш.
     */
    @Synchronized
    fun retry(op: Op): Op {
        val next = op.copy(attempt = op.attempt + 1)
        if (unchanged(op)) write(next)
        return next
    }

    /** Операция в журнале — та же, что читала отправка? Сверяем содержимым, не id. */
    private fun unchanged(op: Op): Boolean =
        File(dir, "${op.id}$EXT")
            .takeIf { it.exists() }
            ?.let { runCatching { read(it) }.getOrNull() } == op

    private fun files(): Sequence<File> =
        dir.listFiles().orEmpty().filter { it.name.endsWith(EXT) }.sortedBy { it.name }.asSequence()

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
                is Edit.AddToRegistry -> json.put("name", edit.name)
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
                "AddToRegistry" -> Edit.AddToRegistry(json.getString("name"))
                "DeleteFile" -> Edit.DeleteFile
                else -> error("неизвестная операция очереди: $type")
            }

        val NULL: Any = JSONObject.NULL

        fun JSONObject.str(key: String): String? = if (isNull(key)) null else getString(key)
    }
}
