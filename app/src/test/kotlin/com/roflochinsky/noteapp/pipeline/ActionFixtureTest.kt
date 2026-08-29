package com.roflochinsky.noteapp.pipeline

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Файл эталонной пары Action — `app/src/test/resources/action/` (см. README рядом с ней). */
internal fun fixture(name: String): String =
    checkNotNull(NoteFile::class.java.getResource("/action/$name")) { "нет фикстуры $name" }
        .readText()

/**
 * Эталонная пара Action — контракт «промпт `docs/examples/process-notes.yml` ↔ парсер приложения».
 * Правило README: промпт меняется только вместе с фикстурой; этот тест делает правило гейтом —
 * разъехались промпт и парсер, падает здесь, а не в рантайме на телефоне.
 *
 * Схема полей — ADR `docs/adr/2026-08-26-tasks-as-files.md`, формат заметки —
 * `docs/specs/2026-08-24-note-format.md`. Расходятся фикстура и парсер — прав формат.
 */
class ActionFixtureTest {

    /** Строка секции «Задачи» v2: `- [заголовок](../tasks/имя.md)`, и ничего кроме. */
    private val link = Regex("""^- \[(.+?)]\(\.\./tasks/(.+?)\)$""", RegexOption.MULTILINE)

    private val note = fixture("note-done-with-tasks.md")

    @Test
    fun `секция Задачи — только ссылки на файлы tasks`() {
        val summary = DoneNoteParser.parse(note)!!.summaryMd
        val section = summary.substringAfter("**Задачи.**").trim()
        assertEquals(
            listOf("Поднять лимит ретраев в очереди экспорта", "Купить кабель для стенда"),
            link.findAll(section).map { it.groupValues[1] }.toList(),
        )
        // Чекбоксов-дублей в заметке нет (ADR v2): каждая строка секции — ссылка.
        assertTrue(section, section.lines().all { link.matches(it) })
    }

    @Test
    fun `ссылки разрешаются в файлы задач, а те ссылаются на ту же заметку`() {
        val parsed = NoteFile.parse("встречи/x.md", note)!!
        // «2026-08-26T09:14:05+03:00» → «2026-08-26-0914»: имя файла заметки (решение 2 спеки).
        val stamp =
            parsed.fields["recorded"]!!
                .take("ГГГГ-ММ-ДДTЧЧ:ММ".length)
                .replace("T", "-")
                .replace(":", "")
        val links = link.findAll(parsed.section(NoteFile.SUMMARY)!!).toList()

        assertEquals(2, links.size)
        for (m in links) {
            val name = m.groupValues[2]
            val task = TaskFile.parse(TaskFile.DIR + name, fixture(name))
            assertEquals(m.groupValues[1], task.title)
            assertEquals(TaskFile.STATUS_OPEN, task.status) // Action создаёт только open
            // source — конечный путь заметки: папка типа, не inbox/ (README пары).
            assertTrue(task.source, task.source!!.contains("/$stamp-"))
            assertFalse(task.source, task.source!!.startsWith("inbox/"))
        }
    }

    @Test
    fun `у задачи-минимума необязательных полей нет вовсе`() {
        val full = task("2026-08-26-podnyat-limit-retraev.md")
        assertEquals("P1", full.priority)
        assertEquals("tgsum", full.project)
        assertEquals(LocalDate.of(2026, 8, 27), full.due)
        assertEquals(listOf("релиз"), full.tags)
        assertEquals(2, full.subtasks.size)
        assertFalse(full.subtasks.any { it.done })

        val bare = task("2026-08-26-kupit-kabel.md")
        assertEquals(TaskFile.PRIORITY_DEFAULT, bare.priority) // умолчание ADR, не поле файла
        assertNull(bare.project)
        assertNull(bare.due)
        assertTrue(bare.tags.isEmpty())
        assertTrue(bare.subtasks.isEmpty())
    }

    private fun task(name: String) = TaskFile.parse(TaskFile.DIR + name, fixture(name))
}
