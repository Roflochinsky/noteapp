package com.roflochinsky.noteapp.pipeline

import org.json.JSONArray
import org.json.JSONObject

/**
 * Пачка правок одним коммитом: чистая сборка тел запросов git data API — дерево поверх `base_tree`,
 * коммит, движение ветки (research §7.2). Сети здесь нет и быть не должно: отправляет их
 * [GithubClient.commitBatch], а решает, что менять, — вызывающий (например [Migration]).
 *
 * Почему это дешевле `contents` API: пачка из двадцати правок — те же пять запросов, и применяется
 * она целиком или не применяется вовсе. Ради этого свойства и заведена миграция «один коммит,
 * откатываемый одним `revert`».
 *
 * ponytail: блобы отдельными запросами не создаём — содержимое едет прямо в записи дерева полем
 * `content` («Use either tree.sha or content to specify the contents of the entry», research §7.2).
 * На каждый изменённый файл это экономит по запросу. Потолок: файл больше пары мегабайт лучше
 * класть через `POST /git/blobs` — в репо заметок таких нет.
 */
object BatchPlan {

    /** Обычный файл в git; исполняемых и симлинков в репо заметок нет. */
    const val MODE = "100644"

    /**
     * Что случится с одним путём. Правка существующего файла — тот же [Put]: дерево перезаписывает
     * запись base_tree с тем же путём.
     */
    sealed interface Change {
        val path: String
    }

    data class Put(override val path: String, val content: String) : Change

    data class Delete(override val path: String) : Change

    /**
     * Переименование = удаление старого пути + создание нового: своей операции у git-дерева нет.
     */
    fun rename(from: String, to: String, content: String): List<Change> =
        listOf(Delete(from), Put(to, content))

    /** `POST /git/trees`: перечисляем только изменённые пути, остальное берётся из [baseTree]. */
    fun tree(baseTree: String, changes: List<Change>): JSONObject {
        val entries = JSONArray()
        changes.forEach { change ->
            val entry = JSONObject().put("path", change.path).put("mode", MODE).put("type", "blob")
            when (change) {
                // `sha: null` — «If the value is null then the file will be deleted».
                is Delete -> entry.put("sha", JSONObject.NULL)
                is Put -> entry.put("content", change.content)
            }
            entries.put(entry)
        }
        return JSONObject().put("base_tree", baseTree).put("tree", entries)
    }

    fun commit(message: String, treeSha: String, parent: String): JSONObject =
        JSONObject()
            .put("message", message)
            .put("tree", treeSha)
            .put("parents", JSONArray().put(parent))

    /**
     * `PATCH ref` с `force: false` — защита от гонки, а не формальность: если ветка сдвинулась,
     * GitHub отклонит обновление, и пачку надо пересобрать на новом HEAD. `force: true` в noteapp
     * не бывает никогда — это перезапись чужой работы.
     */
    fun ref(commitSha: String): JSONObject = JSONObject().put("sha", commitSha).put("force", false)
}
