package com.roflochinsky.noteapp.pipeline

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Файловый журнал очереди записи (решение LLD-6): файл на операцию, порядок сохраняется, склейка по
 * одной цели, отмена снимает операцию до отправки, убитый процесс ничего не теряет.
 */
class WriteQueueTest {

    @get:Rule val tmp = TemporaryFolder()

    private val path = "tasks/2026-08-25-fix-retraev.md"

    private fun queue(dir: java.io.File) = WriteQueue(dir)

    @Test
    fun `два быстрых тапа по одному полю дают одну операцию`() {
        val q = queue(tmp.newFolder())
        q.enqueue(path, Edit.SetStatus(TaskFile.STATUS_DONE, LocalDate.parse("2026-08-26")), "sha1")
        q.enqueue(path, Edit.SetStatus(TaskFile.STATUS_OPEN, null), "sha1")
        val ops = q.pending()
        assertEquals(1, ops.size)
        assertEquals(Edit.SetStatus(TaskFile.STATUS_OPEN, null), ops.single().edit)
    }

    @Test
    fun `правки разных полей не съедают друг друга`() {
        val q = queue(tmp.newFolder())
        q.enqueue(path, Edit.SetField("priority", "P1"), "sha1")
        q.enqueue(path, Edit.SetField("due", "2026-08-28"), "sha1")
        assertEquals(
            listOf(Edit.SetField("priority", "P1"), Edit.SetField("due", "2026-08-28")),
            q.pending().map { it.edit },
        )
    }

    @Test
    fun `отмена снимает операцию из очереди`() {
        val q = queue(tmp.newFolder())
        val op = q.enqueue(path, Edit.SetStatus(TaskFile.STATUS_DONE, null), "sha1")
        q.cancel(op.id)
        assertEquals(emptyList<WriteQueue.Op>(), q.pending())
    }

    @Test
    fun `журнал переживает убитый процесс, порядок и попытки на месте`() {
        val dir = tmp.newFolder()
        val q = queue(dir)
        q.enqueue(path, Edit.SetField("priority", "P1"), "sha1")
        val second = q.enqueue(path, Edit.AddSubtask("Тест на потерю сети"), "sha1")
        q.retry(second)
        val revived = queue(dir).pending()
        assertEquals(
            listOf(Edit.SetField("priority", "P1"), Edit.AddSubtask("Тест на потерю сети")),
            revived.map { it.edit },
        )
        assertEquals(listOf(0, 1), revived.map { it.attempt })
        assertEquals("sha1", revived.first().baseSha)
    }

    @Test
    fun `удаление файла вытесняет прежние правки этого пути`() {
        val q = queue(tmp.newFolder())
        q.enqueue(path, Edit.SetField("priority", "P1"), "sha1")
        q.enqueue("tasks/другая.md", Edit.SetField("priority", "P3"), "sha2")
        q.enqueue(path, Edit.DeleteFile, "sha1")
        assertEquals(listOf("tasks/другая.md", path), q.pending().map { it.path })
        assertTrue(q.pending().last().edit is Edit.DeleteFile)
    }

    @Test
    fun `успешная операция уходит из журнала`() {
        val dir = tmp.newFolder()
        val q = queue(dir)
        val op = q.enqueue(path, Edit.CreateTask("---\ntitle: Новая\n---\n"), null)
        q.done(op)
        assertEquals(emptyList<WriteQueue.Op>(), queue(dir).pending())
    }

    @Test
    fun `битый файл журнала не роняет очередь`() {
        val dir = tmp.newFolder()
        val q = queue(dir)
        q.enqueue(path, Edit.SetField("priority", "P1"), "sha1")
        java.io.File(dir, "000042-bad.json").writeText("{это не json")
        assertEquals(1, q.pending().size)
    }
}
