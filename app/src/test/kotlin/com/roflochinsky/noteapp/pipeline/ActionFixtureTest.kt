package com.roflochinsky.noteapp.pipeline

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Стык «эталонная пара Action ↔ парсер приложения»: файлы `app/src/test/resources/action/`
 * разбираются `NoteFile`/`TaskFile` ровно так, как обещает README рядом с ними. Разъехались
 * фикстура и парсер — падает здесь, а не в рантайме на телефоне.
 *
 * Чего тест не делает: промпт `docs/examples/process-notes.yml` он не читает и правку промпта без
 * правки фикстуры не поймает. Правило README «промпт меняется только вместе с фикстурой» держится
 * на ревью и доках, гейтом его этот тест не делает.
 *
 * Схема полей — ADR `docs/adr/2026-08-26-tasks-as-files.md`, формат заметки —
 * `docs/specs/2026-08-24-note-format.md`. Расходятся фикстура и парсер — прав формат.
 */
class ActionFixtureTest {

    /** Строка секции «Задачи» v2: `- [заголовок](../tasks/имя.md)`, и ничего кроме. */
    private val link = Regex("""^- \[(.+?)]\(\.\./tasks/(.+?)\)$""", RegexOption.MULTILINE)

    private val note = ActionFixture.text("note-done-with-tasks.md")

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

    /** Мета заметки: порядок ключей файла (его `NoteFile` сохраняет) и поля v2 из ADR. */
    @Test
    fun `frontmatter заметки — свой порядок ключей, project и tags на месте`() {
        val parsed = NoteFile.parse("встречи/2026-08-26-0914-limity-retraev.md", note)!!
        assertEquals(
            listOf(
                "recorded",
                "duration",
                "device",
                "type",
                "participants",
                "project",
                "tags",
                "title",
                "status",
            ),
            parsed.fields.keys.toList(),
        )
        assertEquals("tgsum", parsed.project)
        assertEquals(listOf("релиз"), parsed.tags)
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
            val md = ActionFixture.text(name)
            val task = TaskFile.parse(TaskFile.DIR + name, md)
            assertEquals(m.groupValues[1], task.title)
            // Action создаёт только open. Литерал плюс наличие ключа в файле: `open` — ещё и
            // умолчание парсера, сверка с TaskFile.STATUS_OPEN сошлась бы и без поля в файле.
            assertEquals("open", task.status)
            assertTrue(md, "\nstatus: open\n" in md)
            // source — конечный путь заметки: папка типа, не inbox/ (README пары).
            assertTrue(task.source, task.source!!.contains("/$stamp-"))
            assertFalse(task.source, task.source!!.startsWith("inbox/"))
        }
    }

    @Test
    fun `у задачи-минимума обязательные поля есть, необязательных нет вовсе`() {
        val full = task("2026-08-26-podnyat-limit-retraev.md")
        assertEquals("P1", full.priority)
        assertEquals("tgsum", full.project)
        assertEquals(LocalDate.of(2026, 8, 27), full.due)
        assertEquals(listOf("релиз"), full.tags)
        assertEquals(2, full.subtasks.size)
        assertFalse(full.subtasks.any { it.done })

        val bareMd = ActionFixture.text(BARE)
        val bare = TaskFile.parse(TaskFile.DIR + BARE, bareMd)
        // priority промпт держит обязательным даже у минимума. Литерал пинит значение, вторая
        // строка — что поле в файле есть: `P2` совпадает с умолчанием парсера, и сверка с
        // TaskFile.PRIORITY_DEFAULT прошла бы на файле, где поля нет вовсе.
        assertEquals("P2", bare.priority)
        assertTrue(bareMd, "\npriority: P2\n" in bareMd)
        assertNull(bare.project)
        assertNull(bare.due)
        assertTrue(bare.tags.isEmpty())
        assertTrue(bare.subtasks.isEmpty())
    }

    /**
     * «Секция „Транскрипт“ переносится из raw как есть, вместе с пустой строкой после заголовка:
     * Action транскрипт не переписывает» (спека формата). Сверка идёт по тексту файлов, а не через
     * [NoteFile.Note.section]: парсер съел бы пустую строку одинаково в обеих половинах, и сверка
     * прошла бы на файлах, где её уже нет.
     */
    @Test
    fun `транскрипт done-заметки — байт в байт транскрипт raw`() {
        for ((raw, done) in RAW_TO_DONE) {
            val fromRaw = transcript(ActionFixture.text(raw))
            assertEquals(raw, fromRaw, transcript(ActionFixture.text(done)))
            assertTrue(fromRaw, fromRaw.startsWith("$TRANSCRIPT\n\n"))
        }
    }

    /** Хвост файла от заголовка: «Транскрипт» — последняя секция и в raw, и в done. */
    private fun transcript(md: String): String {
        val at = md.indexOf(TRANSCRIPT)
        // Без проверки пропавшая секция дала бы StringIndexOutOfBoundsException вместо диагноза.
        require(at >= 0) { "в фикстуре нет секции $TRANSCRIPT" }
        return md.substring(at)
    }

    private fun task(name: String) = TaskFile.parse(TaskFile.DIR + name, ActionFixture.text(name))

    private companion object {
        const val BARE = "2026-08-26-kupit-kabel.md"
        const val TRANSCRIPT = "## Транскрипт"

        /** Половины эталонной пары: вход от телефона → результат Action. */
        val RAW_TO_DONE =
            listOf(
                "note-raw-with-tasks.md" to "note-done-with-tasks.md",
                "note-raw-no-tasks.md" to "note-done-no-tasks.md",
            )
    }
}
