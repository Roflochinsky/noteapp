package com.roflochinsky.noteapp.pipeline

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Формат задачи — docs/adr/2026-08-26-tasks-as-files.md (единственный источник схемы). */
@Suppress("TooManyFunctions") // тестовый класс — список проверок, а не поверхность класса
class TaskFileTest {

    /** Как файл лежит в репо заметок (снято с Roflochinsky/voice-notes-test). */
    private val canonical =
        """
        ---
        title: Фикс ретраев очереди
        project: tgsum
        priority: P1
        status: open
        source: встречи/2026-08-24-1807-reliz-tgsum.md
        created: 2026-08-25
        due: 2026-08-28
        tags: [релиз]
        ---

        Очередь не переживает потерю сети посреди пуша.

        ## Подзадачи
        - [x] Воспроизвести баг на длинной записи
        - [x] Экспоненциальный бэкофф в PushWorker
        - [ ] Тест на потерю сети посреди пуша
        """
            .trimIndent()

    /** Тот же смысл, написанный руками: лишние пробелы, кавычки, порядок, блочные теги, `- [X]`. */
    private val messy =
        """
        ---
        status:   open
        title:  "Созвон: релиз tgsum"
        priority:P1
        tags:
          - релиз
          - деньги
        project: 'tgsum'
        created: 2026-08-25
        напоминание: не забыть про Диму
        ---

        Описание.

        ## Подзадачи
        - [X]  Первая
        -  [ ] Вторая
        """
            .trimIndent()

    @Test
    fun `разбирает каноничный файл задачи`() {
        val t = TaskFile.parse("tasks/2026-08-25-fix-retraev-ocheredi.md", canonical)
        assertEquals("Фикс ретраев очереди", t.title)
        assertEquals("tgsum", t.project)
        assertEquals("P1", t.priority)
        assertEquals("open", t.status)
        assertEquals("встречи/2026-08-24-1807-reliz-tgsum.md", t.source)
        assertEquals(LocalDate.of(2026, 8, 25), t.created)
        assertEquals(LocalDate.of(2026, 8, 28), t.due)
        assertNull(t.done)
        assertEquals(listOf("релиз"), t.tags)
        assertEquals(3, t.subtasks.size)
        assertEquals(2, t.subtasks.count { it.done })
        assertEquals("Тест на потерю сети посреди пуша", t.subtasks.last().text)
    }

    @Test
    fun `неканоничный ввод читается так же`() {
        val t = TaskFile.parse("tasks/x.md", messy)
        assertEquals("Созвон: релиз tgsum", t.title) // кавычки сняты, двоеточие внутри уцелело
        assertEquals("tgsum", t.project)
        assertEquals("P1", t.priority)
        assertEquals(listOf("релиз", "деньги"), t.tags) // блочный список тегов
        assertEquals(2, t.subtasks.size)
        assertTrue(t.subtasks.first().done) // - [X]
        assertEquals("Первая", t.subtasks.first().text)
        assertFalse(t.subtasks.last().done)
    }

    @Test
    fun `неизвестные ключи frontmatter переживают сборку`() {
        val out = TaskFile.build(TaskFile.parse("tasks/x.md", messy))
        assertTrue(out, out.contains("напоминание: не забыть про Диму"))
    }

    /** Решение LLD-9: ключ есть в файле — он останется, даже когда значение пустое. */
    @Test
    fun `неизвестный ключ без значения не теряется`() {
        val src = messy.replace("напоминание:", "черновик:\nнапоминание:")
        val out = TaskFile.build(TaskFile.parse("tasks/x.md", src))
        assertEquals("", TaskFile.parse("tasks/x.md", out).extra["черновик"])
        assertEquals(out, TaskFile.build(TaskFile.parse("tasks/x.md", out)))
    }

    @Test
    fun `заголовок с двоеточием при сборке закавычивается`() {
        val out = TaskFile.build(TaskFile.parse("tasks/x.md", messy))
        assertTrue(out, out.contains("""title: "Созвон: релиз tgsum""""))
        assertEquals("Созвон: релиз tgsum", TaskFile.parse("tasks/x.md", out).title)
    }

    @Test
    fun `теги пишутся всегда инлайном`() {
        val out = TaskFile.build(TaskFile.parse("tasks/x.md", messy))
        assertTrue(out, out.contains("tags: [релиз, деньги]"))
    }

    @Test
    fun `канонизация идемпотентна`() {
        for (src in listOf(canonical, messy, canonical.replace("status: open", "status: done"))) {
            val once = TaskFile.build(TaskFile.parse("tasks/x.md", src))
            val twice = TaskFile.build(TaskFile.parse("tasks/x.md", once))
            assertEquals(once, twice)
        }
    }

    @Test
    fun `дата закрытия читается и пишется`() {
        val src = canonical.replace("status: open", "status: done\ndone: 2026-08-26")
        val t = TaskFile.parse("tasks/x.md", src)
        assertEquals("done", t.status)
        assertEquals(LocalDate.of(2026, 8, 26), t.done)
        assertTrue(TaskFile.build(t).contains("done: 2026-08-26"))
        assertFalse(TaskFile.build(TaskFile.parse("tasks/x.md", canonical)).contains("done:"))
    }

    @Test
    fun `пустые поля опускаются, умолчания проставляются`() {
        val bare =
            """
            ---
            title: Разобрать фото с похода
            status: done
            created: 2026-08-20
            ---
            """
                .trimIndent()
        val t = TaskFile.parse("tasks/2026-08-20-razobrat-foto.md", bare)
        assertEquals("P2", t.priority) // умолчание ADR
        assertNull(t.project)
        assertNull(t.due)
        assertTrue(t.tags.isEmpty())
        assertTrue(t.subtasks.isEmpty())
        val out = TaskFile.build(t)
        assertFalse(out, out.contains("project:"))
        assertFalse(out, out.contains("tags:"))
    }

    @Test
    fun `имя файла — дата плюс транслит слага`() {
        val date = LocalDate.of(2026, 8, 25)
        assertEquals(
            "2026-08-25-fiks-retraev-ocheredi.md",
            TaskFile.fileName(date, "Фикс ретраев очереди", emptySet()),
        )
        assertEquals(
            "2026-08-25-kupit-perekhodnik-usb-c.md",
            TaskFile.fileName(date, "Купить переходник USB-C!", emptySet()),
        )
    }

    @Test
    fun `при коллизии имени добавляется суффикс`() {
        val date = LocalDate.of(2026, 8, 25)
        val taken = setOf("tasks/2026-08-25-zvonok.md", "tasks/2026-08-25-zvonok-2.md")
        assertEquals("2026-08-25-zvonok-3.md", TaskFile.fileName(date, "Звонок", taken))
    }
}
