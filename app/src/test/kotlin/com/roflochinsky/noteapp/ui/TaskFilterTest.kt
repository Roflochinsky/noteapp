package com.roflochinsky.noteapp.ui

import com.roflochinsky.noteapp.pipeline.TaskFile
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Порядок и рубрики списка задач (решение LLD-16 + вердикт UX): `today` — параметром. */
class TaskFilterTest {

    private val today = LocalDate.of(2026, 8, 26)

    private fun task(
        title: String,
        priority: String = "P2",
        status: String = TaskFile.STATUS_OPEN,
        due: String? = null,
        done: String? = null,
    ) =
        TaskFile.Task(
            path = "tasks/$title.md",
            title = title,
            priority = priority,
            status = status,
            created = LocalDate.of(2026, 8, 1),
            due = due?.let(LocalDate::parse),
            done = done?.let(LocalDate::parse),
        )

    @Test
    fun `сначала приоритет, внутри просроченные, потом по сроку, потом без срока по created`() {
        val tasks =
            listOf(
                task("P2 без срока, позже").copy(created = LocalDate.of(2026, 8, 10)),
                task("P3 со сроком", priority = "P3", due = "2026-08-27"),
                task("P1 со сроком", priority = "P1", due = "2026-08-28"),
                task("P2 без срока, раньше").copy(created = LocalDate.of(2026, 8, 2)),
                task("P1 просрочена", priority = "P1", due = "2026-08-24"),
            )
        assertEquals(
            listOf(
                "P1 просрочена",
                "P1 со сроком",
                "P2 без срока, раньше",
                "P2 без срока, позже",
                "P3 со сроком",
            ),
            TaskFilter.open(tasks, today).map { it.title },
        )
    }

    @Test
    fun `открытые — это всё, что не done, включая в работе`() {
        val tasks =
            listOf(
                task("открыта"),
                task("в работе", status = TaskFile.STATUS_IN_PROGRESS),
                task("сделана", status = TaskFile.STATUS_DONE, done = "2026-08-25"),
            )
        assertEquals(
            setOf("открыта", "в работе"),
            TaskFilter.open(tasks, today).map { it.title }.toSet(),
        )
    }

    @Test
    fun `просрочка — только у незакрытых со сроком в прошлом`() {
        assertTrue(TaskFilter.isOverdue(task("вчера", due = "2026-08-25"), today))
        assertFalse(TaskFilter.isOverdue(task("сегодня", due = "2026-08-26"), today))
        assertFalse(TaskFilter.isOverdue(task("без срока"), today))
        assertFalse(
            TaskFilter.isOverdue(
                task(
                    "закрыта",
                    status = TaskFile.STATUS_DONE,
                    due = "2026-08-25",
                    done = "2026-08-25",
                ),
                today,
            )
        )
    }

    @Test
    fun `сделано за месяц — свежие сверху, старьё не показывается`() {
        val tasks =
            listOf(
                task("закрыта давно", status = TaskFile.STATUS_DONE, done = "2026-06-01"),
                task("закрыта вчера", status = TaskFile.STATUS_DONE, done = "2026-08-25"),
                task("закрыта неделю назад", status = TaskFile.STATUS_DONE, done = "2026-08-19"),
                task("открыта"),
            )
        assertEquals(
            listOf("закрыта вчера", "закрыта неделю назад"),
            TaskFilter.done(tasks, today).map { it.title },
        )
        assertEquals(2, TaskFilter.doneCount(tasks, today))
    }

    @Test
    fun `сделанные без даты закрытия идут в конец и в счётчик не входят`() {
        val tasks =
            listOf(
                task("без даты", status = TaskFile.STATUS_DONE),
                task("закрыта вчера", status = TaskFile.STATUS_DONE, done = "2026-08-25"),
            )
        assertEquals(
            listOf("закрыта вчера", "без даты"),
            TaskFilter.done(tasks, today).map { it.title },
        )
        assertEquals(1, TaskFilter.doneCount(tasks, today))
    }

    @Test
    fun `подписи срока — словом, просрочка отдельной подписью`() {
        assertEquals("сегодня", dueLabel(today, today))
        assertEquals("завтра", dueLabel(today.plusDays(1), today))
        assertEquals("до пт", dueLabel(LocalDate.of(2026, 8, 28), today))
        assertEquals("до 5 сен", dueLabel(LocalDate.of(2026, 9, 5), today))
        assertEquals("просрочено · вчера", overdueLabel(today.minusDays(1), today))
        assertEquals("просрочено · 20 авг", overdueLabel(LocalDate.of(2026, 8, 20), today))
    }

    @Test
    fun `рубрики приоритета идут P1 P2 P3 и пустых нет`() {
        val tasks = listOf(task("низкая", priority = "P3"), task("высокая", priority = "P1"))
        assertEquals(listOf("P1", "P3"), TaskFilter.byPriority(tasks, today).map { it.first })
        assertEquals(
            listOf("высокая", "низкая"),
            TaskFilter.byPriority(tasks, today).flatMap { it.second }.map { it.title },
        )
    }
}
