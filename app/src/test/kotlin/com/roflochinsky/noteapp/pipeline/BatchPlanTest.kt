package com.roflochinsky.noteapp.pipeline

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тела запросов git data API (research §7.2): дерево поверх `base_tree`, коммит с одним родителем,
 * `PATCH ref` с `force: false`. Сети здесь нет — [BatchPlan] чистый, его и проверяем.
 */
class BatchPlanTest {

    private fun entries(json: JSONObject): List<JSONObject> {
        val tree = json.getJSONArray("tree")
        return (0 until tree.length()).map { tree.getJSONObject(it) }
    }

    @Test
    fun `создание файла едет содержимым, а не отдельным блобом`() {
        val body = BatchPlan.tree("tree-base", listOf(BatchPlan.Put("tasks/a.md", "текст")))
        assertEquals("tree-base", body.getString("base_tree"))
        val entry = entries(body).single()
        assertEquals("tasks/a.md", entry.getString("path"))
        assertEquals("100644", entry.getString("mode"))
        assertEquals("blob", entry.getString("type"))
        assertEquals("текст", entry.getString("content"))
        assertFalse(entry.toString(), entry.has("sha"))
    }

    @Test
    fun `удаление — это sha null, и именно null, а не пустая строка`() {
        val body = BatchPlan.tree("tree-base", listOf(BatchPlan.Delete("tasks/a.md")))
        val entry = entries(body).single()
        assertEquals("tasks/a.md", entry.getString("path"))
        assertSame(JSONObject.NULL, entry.get("sha"))
        assertFalse(entry.toString(), entry.has("content"))
    }

    @Test
    fun `переименование — удаление плюс создание, порядок сохраняется`() {
        val body = BatchPlan.tree("tree-base", BatchPlan.rename("inbox/a.md", "идеи/a.md", "текст"))
        val (gone, born) = entries(body)
        assertEquals("inbox/a.md", gone.getString("path"))
        assertSame(JSONObject.NULL, gone.get("sha"))
        assertEquals("идеи/a.md", born.getString("path"))
        assertEquals("текст", born.getString("content"))
    }

    @Test
    fun `коммит ссылается на новое дерево и ровно на один родительский коммит`() {
        val body = BatchPlan.commit("migration: tasks v2", "tree-new", "head-1")
        assertEquals("migration: tasks v2", body.getString("message"))
        assertEquals("tree-new", body.getString("tree"))
        assertEquals(listOf("head-1"), body.getJSONArray("parents").let { listOf(it.getString(0)) })
        assertEquals(1, body.getJSONArray("parents").length())
    }

    @Test
    fun `ветка двигается без force — чужую работу не перезаписываем никогда`() {
        val body = BatchPlan.ref("commit-new")
        assertEquals("commit-new", body.getString("sha"))
        assertFalse(body.getBoolean("force"))
    }

    @Test
    fun `пустой список изменений тела дерева не даёт`() {
        assertTrue(entries(BatchPlan.tree("tree-base", emptyList())).isEmpty())
    }
}
