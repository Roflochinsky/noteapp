package com.roflochinsky.noteapp.pipeline

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Смысловая правка поверх текста файла (решение LLD-1 и LLD-9): frontmatter правится построчно,
 * тело файла не пересобирается, неизвестные ключи остаются на месте.
 */
class EditTest {

    private val src =
        """
        ---
        title: Фикс ретраев очереди
        project: tgsum
        priority: P1
        status: open
        created: 2026-08-25
        claude_hint: не наше поле
        ---

        Свободный текст-описание.

        ## Подзадачи
        - [x] Воспроизвести баг на длинной записи
        - [ ] Экспоненциальный бэкофф в PushWorker
        """
            .trimIndent()

    @Test
    fun `правка поля сохраняет неизвестные ключи и тело файла`() {
        val out = Edit.apply(src, Edit.SetField("priority", "P3"))
        assertTrue(out, out.contains("priority: P3"))
        assertFalse(out, out.contains("priority: P1"))
        assertTrue("неизвестный ключ пропал", out.contains("claude_hint: не наше поле"))
        assertTrue("тело переписано", out.contains("Свободный текст-описание."))
        assertTrue(out.contains("- [x] Воспроизвести баг на длинной записи"))
    }

    @Test
    fun `новый ключ встаёт на своё место по ADR, а не в хвост`() {
        val out = Edit.apply(src, Edit.SetField("due", "2026-08-28"))
        val keys = out.lines().takeWhile { it != "---" || out.lines().indexOf(it) == 0 }
        assertTrue(out, out.contains("created: 2026-08-25\ndue: 2026-08-28\n"))
        assertTrue(keys.isNotEmpty())
    }

    @Test
    fun `пустое значение убирает ключ, остальные не двигаются`() {
        val out = Edit.apply(src, Edit.SetField("project", null))
        assertFalse(out, out.contains("project:"))
        assertTrue(out, out.contains("title: Фикс ретраев очереди\npriority: P1"))
    }

    @Test
    fun `статус и дата закрытия ходят парой`() {
        val done =
            Edit.apply(src, Edit.SetStatus(TaskFile.STATUS_DONE, LocalDate.parse("2026-08-26")))
        assertTrue(done, done.contains("status: done"))
        assertTrue(done, done.contains("done: 2026-08-26"))
        val back = Edit.apply(done, Edit.SetStatus(TaskFile.STATUS_OPEN, null))
        assertTrue(back, back.contains("status: open"))
        assertFalse(back, back.contains("done: 2026-08-26"))
    }

    @Test
    fun `подзадача находится по тексту, а не по позиции`() {
        val moved =
            src.replace(
                "- [x] Воспроизвести баг на длинной записи\n- [ ] Экспоненциальный бэкофф в PushWorker",
                "- [ ] Экспоненциальный бэкофф в PushWorker\n- [x] Воспроизвести баг на длинной записи",
            )
        val out = Edit.apply(moved, Edit.ToggleSubtask("экспоненциальный  бэкофф в pushworker", true))
        assertTrue(out, out.contains("- [x] Экспоненциальный бэкофф в PushWorker"))
        assertTrue("чужая подзадача изменилась", out.contains("- [x] Воспроизвести баг"))
    }

    @Test
    fun `подзадача добавляется в конец секции`() {
        val out = Edit.apply(src, Edit.AddSubtask("Тест на потерю сети"))
        val subs = TaskFile.parse("tasks/x.md", out).subtasks
        assertEquals(3, subs.size)
        assertEquals("Тест на потерю сети", subs.last().text)
        assertFalse(subs.last().done)
    }

    @Test
    fun `секция подзадач заводится, если её не было`() {
        val bare = "---\ntitle: Купить переходник\nstatus: open\n---\n"
        val out = Edit.apply(bare, Edit.AddSubtask("Проверить USB-C"))
        assertEquals(listOf("Проверить USB-C"), TaskFile.parse("tasks/x.md", out).subtasks.map { it.text })
        assertTrue(out, out.contains(Edit.SUBTASKS_HEADING))
    }

    @Test
    fun `правки не ломают разбор и накладываются друг на друга`() {
        var out = src
        listOf(
                Edit.SetField("priority", "P3"),
                Edit.SetField("tags", "[релиз, срочно]"),
                Edit.SetTitle("Фикс ретраев очереди v2"),
                Edit.AddSubtask("Тест на потерю сети"),
                Edit.ToggleSubtask("Экспоненциальный бэкофф в PushWorker", true),
            )
            .forEach { out = Edit.apply(out, it) }
        val task = TaskFile.parse("tasks/2026-08-25-fix.md", out)
        assertEquals("Фикс ретраев очереди v2", task.title)
        assertEquals("P3", task.priority)
        assertEquals(listOf("релиз", "срочно"), task.tags)
        assertEquals(3, task.subtasks.size)
        assertEquals(2, task.subtasks.count { it.done })
        assertEquals(mapOf("claude_hint" to "не наше поле"), task.extra)
        assertTrue(out, out.contains("Свободный текст-описание."))
    }

    @Test
    fun `создание несёт готовый текст, удаление текст не трогает`() {
        val body = "---\ntitle: Новая\n---\n"
        assertEquals(body, Edit.apply("", Edit.CreateTask(body)))
        assertEquals(src, Edit.apply(src, Edit.DeleteFile))
    }
}
