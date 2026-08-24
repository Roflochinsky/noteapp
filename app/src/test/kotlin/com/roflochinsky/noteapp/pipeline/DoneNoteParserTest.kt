package com.roflochinsky.noteapp.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Парсер done-заметки из репо (решение E: саммари показываем в приложении). */
class DoneNoteParserTest {

    private val done =
        """
        ---
        recorded: 2026-08-24T23:51:49+03:00
        duration: 00:18
        device: CPH2649
        type: задача
        participants: [Иван Гай]
        title: Добавить репозиторий в Климит
        status: done
        ---

        ## Саммари
        **Суть.** Шутливое предложение.
        **Задачи.**
        - [ ] Иван Гай — добавить репозиторий

        ## Транскрипт

        [00:00] Спикер 1: текст
        """
            .trimIndent()

    @Test
    fun `парсит мету и саммари из done-файла`() {
        val n = DoneNoteParser.parse(done)!!
        assertEquals("Добавить репозиторий в Климит", n.title)
        assertEquals("задача", n.type)
        assertEquals(listOf("Иван Гай"), n.participants)
        assertTrue(n.summaryMd.contains("**Суть.** Шутливое предложение."))
        assertTrue(!n.summaryMd.contains("## Транскрипт"))
    }

    @Test
    fun `raw-файл без саммари даёт null`() {
        val raw = done.replace("status: done", "status: raw")
        assertNull(DoneNoteParser.parse(raw))
    }

    @Test
    fun `пустые participants парсятся в пустой список`() {
        val n = DoneNoteParser.parse(done.replace("[Иван Гай]", "[]"))!!
        assertTrue(n.participants.isEmpty())
    }
}
