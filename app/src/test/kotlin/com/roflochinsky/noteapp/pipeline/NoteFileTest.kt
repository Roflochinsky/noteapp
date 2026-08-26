package com.roflochinsky.noteapp.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Заметка — docs/specs/2026-08-24-note-format.md + поля `project`/`tags` из ADR v2. Репо живёт в
 * смешанном состоянии: часть заметок v1 (задачи чекбоксами), часть v2 (ссылки на файлы).
 */
class NoteFileTest {

    /** v2: задачи ссылками (реальный файл встречи из Roflochinsky/voice-notes-test). */
    private val v2 =
        """
        ---
        recorded: 2026-08-24T18:07:32+03:00
        duration: 12:31
        device: OnePlus 13
        type: встреча
        participants: [Дима, Никита]
        project: tgsum
        tags: [релиз]
        title: Созвон с Димой — релиз tgsum
        status: done
        ---

        ## Саммари
        **Суть.** Релиз tgsum v0.2 назначен на пятницу.

        **Задачи.**
        - [Фикс ретраев очереди](../tasks/2026-08-25-fix-retraev-ocheredi.md)

        ## Транскрипт
        [00:12] Дима: Смотри, по релизу: я бы закрыл экспорт тем до пятницы.
        """
            .trimIndent()

    /** v1: та же секция «Задачи», но чекбоксом — до миграции такие заметки в репо есть. */
    private val v1 =
        """
        ---
        recorded: 2026-08-12T19:22:10+03:00
        duration: 03:05
        device: OnePlus 13
        type: идея
        participants: [Дима]
        title: Дима — идея про экспорт в Notion
        status: done
        ---

        ## Саммари
        **Суть.** Дима предлагает экспорт прямо в Notion через их API.

        **Задачи.**
        - [ ] посмотреть лимиты Notion API

        ## Транскрипт
        [00:04] Дима: А давай экспорт сразу в Notion, у них есть API.
        """
            .trimIndent()

    @Test
    fun `разбирает заметку v2 с полями проекта и тегов`() {
        val n = NoteFile.parse("встречи/2026-08-24-1807-reliz-tgsum.md", v2)!!
        assertEquals("Созвон с Димой — релиз tgsum", n.title)
        assertEquals("встреча", n.type)
        assertEquals("done", n.status)
        assertEquals(listOf("Дима", "Никита"), n.participants)
        assertEquals("tgsum", n.project)
        assertEquals(listOf("релиз"), n.tags)
        assertTrue(n.section("## Саммари")!!.contains("**Суть.**"))
        assertTrue(!n.section("## Саммари")!!.contains("## Транскрипт"))
    }

    @Test
    fun `разбирает заметку v1 с чекбоксом и не теряет текст`() {
        val n = NoteFile.parse("идеи/2026-08-12-eksport-v-notion.md", v1)!!
        assertEquals("идея", n.type)
        assertNull(n.project)
        assertTrue(n.tags.isEmpty())
        assertTrue(n.body.contains("- [ ] посмотреть лимиты Notion API"))
        assertTrue(n.body.contains("[00:04] Дима:"))
    }

    @Test
    fun `raw-заметка телефона тоже разбирается`() {
        val raw =
            RawNote.build(
                RawNote.Input(
                    noteId = "20260824-235149",
                    zone = java.time.ZoneOffset.UTC,
                    durationSec = 18,
                    device = "OnePlus 13",
                    transcriptMd = "[00:00] Спикер 1: текст",
                )
            )
        val n = NoteFile.parse("inbox/2026-08-24-2351.md", raw)!!
        assertEquals("raw", n.status)
        assertNull(n.title)
        assertNull(n.section("## Саммари"))
    }

    @Test
    fun `не наш формат даёт null`() {
        assertNull(NoteFile.parse("readme.md", "просто текст без frontmatter"))
    }

    @Test
    fun `сборка идемпотентна и сохраняет транскрипт`() {
        for (src in listOf(v1, v2)) {
            val once = NoteFile.build(NoteFile.parse("x.md", src)!!)
            val twice = NoteFile.build(NoteFile.parse("x.md", once)!!)
            assertEquals(once, twice)
            assertTrue(once.contains("## Транскрипт"))
            assertTrue(once.contains("recorded: 2026-08"))
        }
    }
}
