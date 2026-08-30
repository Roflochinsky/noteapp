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
                // Со сроком и без — в одной рубрике: иначе правило «срок выше отсутствия срока»
                // не проверяется ничем (мутация ключа переживала прежнюю фикстуру).
                task("P1 без срока", priority = "P1"),
            )
        assertEquals(
            listOf(
                "P1 просрочена",
                "P1 со сроком",
                "P1 без срока",
                "P2 без срока, раньше",
                "P2 без срока, позже",
                "P3 со сроком",
            ),
            TaskFilter.open(tasks, today).map { it.title },
        )
    }

    /** Вердикт UX: «Задач пока нет» и рубрика «Сделано» на одном экране — враньё экрана. */
    @Test
    fun `пусто — только когда нет ни открытых, ни свежесделанных`() {
        assertTrue(TaskFilter.nothingToShow(emptyList(), today))
        assertFalse(TaskFilter.nothingToShow(listOf(task("Открытая")), today))
        val fresh = task("Сделана вчера", status = TaskFile.STATUS_DONE, done = "2026-08-25")
        assertFalse(TaskFilter.nothingToShow(listOf(fresh), today))
        val stale = task("Сделана зимой", status = TaskFile.STATUS_DONE, done = "2026-01-01")
        assertTrue(TaskFilter.nothingToShow(listOf(stale), today))
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
    fun `слово рубрики — только у известных приоритетов`() {
        assertEquals("высокий", priorityWord("P1"))
        assertEquals("обычный", priorityWord("P2"))
        assertEquals("низкий", priorityWord("P3"))
        // Чужой приоритет из frontmatter не притворяется «обычным» — рубрика остаётся без слова.
        assertEquals("", priorityWord("P0"))
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

    // ── чипы, счётчики и поиск (срез Н3) ──────────────────────────────────────────────────

    private val zoo =
        listOf(
            // today = 2026-08-26. Сроки и теги расставлены так, чтобы каждое окно «Срока» ловило
            // свою задачу, а «Фикс ретраев» имел ДВА тега: с одним тегом на задачу разница между
            // «тег в списке» и «первый тег» ничем бы не проверялась.
            task("Фикс ретраев", priority = "P1", due = "2026-08-28")
                .copy(project = "tgsum", tags = listOf("релиз", "деньги")),
            task("Экспорт тем").copy(project = "tgsum", tags = listOf("релиз")),
            task("Виджет очереди").copy(project = "noteapp"),
            task("Забрать посылку", due = "2026-08-26"),
            task(
                    "Разобрать фото",
                    priority = "P3",
                    status = TaskFile.STATUS_IN_PROGRESS,
                    due = "2026-08-20",
                )
                .copy(tags = listOf("личное")),
            // Закрытая задача с прошедшим сроком: «просроченные» её не берут — как и строка списка.
            task(
                    "Старый долг",
                    status = TaskFile.STATUS_DONE,
                    due = "2026-08-20",
                    done = "2026-08-25",
                )
                .copy(project = "tgsum"),
        )

    private fun titles(filter: TaskFilter.Filter) =
        filter.select(zoo, today).map { it.title }.toSet()

    @Test
    fun `чипы сужают список и складываются друг с другом`() {
        assertEquals(
            setOf("Фикс ретраев", "Экспорт тем", "Старый долг"),
            titles(TaskFilter.Filter(project = "tgsum")),
        )
        assertEquals(
            setOf("Фикс ретраев"),
            titles(TaskFilter.Filter(project = "tgsum", priority = "P1")),
        )
        assertEquals(
            setOf("Фикс ретраев", "Экспорт тем"),
            titles(TaskFilter.Filter(project = "tgsum", status = TaskFile.STATUS_OPEN)),
        )
    }

    @Test
    fun `«Без проекта» — своё значение фильтра, а не «любой проект»`() {
        assertEquals(
            setOf("Забрать посылку", "Разобрать фото"),
            titles(TaskFilter.Filter(project = TaskFilter.NO_PROJECT)),
        )
        assertEquals(zoo.map { it.title }.toSet(), titles(TaskFilter.Filter()))
    }

    /** Вердикт UX: свой фильтр из расчёта исключён — иначе у невыбранных значений всегда 0. */
    @Test
    fun `счётчик в шторке фасетный — свой фильтр в расчёт не идёт`() {
        val filter = TaskFilter.Filter(project = "tgsum")
        val counts = filter.counts(zoo, TaskFilter.Facet.PROJECT, listOf("tgsum", "noteapp"), today)
        assertEquals(3, counts["tgsum"])
        // noteapp остался бы нулём, если бы «Проект: tgsum» считали вместе со своим же фасетом.
        assertEquals(1, counts["noteapp"])
        // «Все проекты» — сколько станет, если сбросить именно этот чип.
        assertEquals(zoo.size, counts[null])
    }

    @Test
    fun `чужие фильтры счётчик учитывает — иначе он обещает больше, чем покажет`() {
        val filter = TaskFilter.Filter(priority = "P1", project = "tgsum")
        val counts = filter.counts(zoo, TaskFilter.Facet.PROJECT, listOf("tgsum", "noteapp"), today)
        assertEquals(1, counts["tgsum"])
        assertEquals(0, counts["noteapp"])
    }

    @Test
    fun `значение реестра без задач остаётся в шторке со счётчиком 0`() {
        val counts =
            TaskFilter.Filter().counts(zoo, TaskFilter.Facet.PROJECT, listOf("workwatch"), today)
        assertEquals(0, counts["workwatch"])
    }

    @Test
    fun `поиск — подстрока заголовка, регистр не важен, поверх активных фильтров`() {
        assertEquals(setOf("Фикс ретраев"), titles(TaskFilter.Filter(query = "ретра")))
        assertEquals(setOf("Фикс ретраев"), titles(TaskFilter.Filter(query = "ФИКС")))
        assertEquals(setOf("Разобрать фото"), titles(TaskFilter.Filter(query = "  фото ")))
        assertEquals(
            emptySet<String>(),
            titles(TaskFilter.Filter(project = "noteapp", query = "фото")),
        )
    }

    // ── чипы «Тег» и «Срок» (срез Н3, добавка `bd nikitatrubaev-0rk.25`) ──────────────────

    @Test
    fun `чип тега сужает список по любому из тегов задачи, а не по первому`() {
        assertEquals(setOf("Фикс ретраев", "Экспорт тем"), titles(TaskFilter.Filter(tag = "релиз")))
        assertEquals(setOf("Фикс ретраев"), titles(TaskFilter.Filter(tag = "деньги")))
        assertEquals(setOf("Разобрать фото"), titles(TaskFilter.Filter(tag = "личное")))
        assertEquals(
            setOf("Экспорт тем"),
            titles(TaskFilter.Filter(tag = "релиз", project = "tgsum", priority = "P2")),
        )
    }

    /** Срок — не значение, а окно; «сегодня» лежит внутри «на неделе» — это выбор, не рубрика. */
    @Test
    fun `чип срока сужает список окном`() {
        assertEquals(
            setOf("Забрать посылку"),
            titles(TaskFilter.Filter(due = TaskFilter.DUE_TODAY)),
        )
        assertEquals(
            setOf("Забрать посылку", "Фикс ретраев"),
            titles(TaskFilter.Filter(due = TaskFilter.DUE_WEEK)),
        )
        assertEquals(
            setOf("Экспорт тем", "Виджет очереди"),
            titles(TaskFilter.Filter(due = TaskFilter.DUE_NONE)),
        )
    }

    /** У закрытой задачи просрочки нет — то же правило, что у [TaskFilter.isOverdue] в списке. */
    @Test
    fun `«просроченные» не берут закрытую задачу с прошедшим сроком`() {
        assertEquals(
            setOf("Разобрать фото"),
            titles(TaskFilter.Filter(due = TaskFilter.DUE_OVERDUE)),
        )
    }

    /** Вердикт UX: свой фильтр из расчёта исключён — иначе у невыбранных окон всегда 0. */
    @Test
    fun `счётчик чипа «Срок» фасетный, пустое окно остаётся с нулём`() {
        val counts =
            TaskFilter.Filter(due = TaskFilter.DUE_TODAY)
                .counts(zoo, TaskFilter.Facet.DUE, TaskFilter.DUES, today)
        assertEquals(1, counts[TaskFilter.DUE_TODAY])
        // Осталось бы нулём, если бы «Срок: сегодня» считали вместе со своим же фасетом.
        assertEquals(1, counts[TaskFilter.DUE_OVERDUE])
        assertEquals(2, counts[TaskFilter.DUE_WEEK])
        assertEquals(2, counts[TaskFilter.DUE_NONE])
        assertEquals(zoo.size, counts[null])
        // Чужой чип счётчик учитывает, и окно без задач не пропадает, а показывает 0.
        val narrowed =
            TaskFilter.Filter(project = "noteapp")
                .counts(zoo, TaskFilter.Facet.DUE, TaskFilter.DUES, today)
        assertEquals(0, narrowed[TaskFilter.DUE_TODAY])
        assertEquals(1, narrowed[TaskFilter.DUE_NONE])
    }

    @Test
    fun `счётчик чипа «Тег» фасетный — теги считаются как множественные`() {
        val counts =
            TaskFilter.Filter(tag = "личное")
                .counts(zoo, TaskFilter.Facet.TAG, listOf("деньги", "личное", "релиз"), today)
        assertEquals(2, counts["релиз"])
        assertEquals(1, counts["деньги"])
        assertEquals(1, counts["личное"])
        assertEquals(zoo.size, counts[null])
    }

    @Test
    fun `пусто под фильтром — не то же, что «задач нет вовсе»`() {
        val filter = TaskFilter.Filter(project = "workwatch")
        assertTrue(filter.active)
        assertTrue(TaskFilter.Filter(tag = "релиз").active)
        assertTrue(TaskFilter.Filter(due = TaskFilter.DUE_TODAY).active)
        assertFalse(TaskFilter.Filter().active)
        assertTrue(TaskFilter.nothingToShow(filter.select(zoo, today), today))
        assertFalse(TaskFilter.nothingToShow(zoo, today))
    }
}
