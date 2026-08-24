package com.roflochinsky.noteapp.pipeline

import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Формат raw-заметки — строго по docs/specs/2026-08-24-note-format.md. */
class RawNoteTest {

    @Test
    fun `frontmatter содержит все поля формата`() {
        val md =
            RawNote.build(
                RawNote.Input(
                    noteId = "20260824-180732",
                    zone = ZoneOffset.ofHours(3),
                    durationSec = 751,
                    device = "OnePlus 13",
                    transcriptMd = "[00:12] Спикер 1: привет",
                )
            )
        assertTrue(md.startsWith("---\n"))
        assertTrue(md.contains("recorded: 2026-08-24T18:07:32+03:00"))
        assertTrue(md.contains("duration: 12:31"))
        assertTrue(md.contains("device: OnePlus 13"))
        assertTrue(md.contains("status: raw"))
        assertTrue(md.contains("## Транскрипт\n\n[00:12] Спикер 1: привет"))
    }

    @Test
    fun `моменты пишутся секцией только когда есть`() {
        val with =
            RawNote.build(
                RawNote.Input("20260824-180732", ZoneOffset.UTC, 60, "d", listOf(31_000L), "t")
            )
        assertTrue(with.contains("## Моменты\n\n- [00:31]"))
        val without =
            RawNote.build(
                RawNote.Input("20260824-180732", ZoneOffset.UTC, 60, "d", emptyList(), "t")
            )
        assertFalse(without.contains("## Моменты"))
    }

    @Test
    fun `имя файла - дата-время без секунд`() {
        assertEquals("2026-08-24-1807.md", RawNote.fileName("20260824-180732"))
    }
}
