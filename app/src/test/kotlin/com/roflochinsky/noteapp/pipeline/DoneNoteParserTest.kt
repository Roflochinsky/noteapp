package com.roflochinsky.noteapp.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Парсер done-заметки из репо (решение E: саммари показываем в приложении).
 *
 * Вход — эталонная пара Action `app/src/test/resources/action/`, а не сочинённая здесь строка:
 * инлайн-фикстура молча осталась в формате v1 (задача чекбоксом, блоки саммари без пустой строки) и
 * парсер против v2 не проверял вообще.
 */
class DoneNoteParserTest {

    private val done = ActionFixture.text("note-done-with-tasks.md")

    @Test
    fun `парсит мету и саммари из done-файла`() {
        val n = DoneNoteParser.parse(done)!!
        assertEquals("Планёрка — лимиты ретраев в tgsum", n.title)
        assertEquals("встреча", n.type)
        assertEquals(listOf("Дима", "Никита", "Серёга"), n.participants)
        assertTrue(n.summaryMd, n.summaryMd.startsWith("**Суть.**"))
        assertFalse(n.summaryMd, n.summaryMd.contains("## Транскрипт"))
    }

    /** Спека: блоки саммари разделены пустой строкой — без неё GitHub слепит их в один абзац. */
    @Test
    fun `блоки саммари разделены пустой строкой`() {
        val summary = DoneNoteParser.parse(done)!!.summaryMd
        assertTrue(summary, summary.contains("\n\n**Ключевое.**"))
        assertTrue(summary, summary.contains("\n\n**Задачи.**"))
    }

    /** null даёт именно статус: саммари в файле на месте, но заметку ещё не подтвердил Action. */
    @Test
    fun `заметка в статусе raw даёт null, даже когда саммари в файле уже есть`() {
        val raw = done.replace("status: done", "status: raw")
        assertTrue(raw, raw.contains(NoteFile.SUMMARY))
        assertNull(DoneNoteParser.parse(raw))
    }

    /** Критерий 4 спеки: задач нет — секции нет; участников нет — `participants: []`. */
    @Test
    fun `заметка без задач разбирается, секции Задачи в ней нет`() {
        val n = DoneNoteParser.parse(ActionFixture.text("note-done-no-tasks.md"))!!
        assertEquals("Идея — маршрут вдоль набережной", n.title)
        assertEquals("идея", n.type)
        assertTrue(n.participants.isEmpty())
        assertFalse(n.summaryMd, n.summaryMd.contains("Задачи"))
    }
}
