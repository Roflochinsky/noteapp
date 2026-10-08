package com.roflochinsky.noteapp.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommunicationExportTest {
    @Test
    fun `queued offline edits are explicitly unconfirmed in exported snapshot`() {
        val note = NoteFile.Note("другое/a.md", mapOf("title" to "Note"), "body")
        val export =
            CommunicationExport.build(
                null,
                emptyList(),
                listOf(FeedItem("a", null, note)),
                listOf(TaskFile.Task("tasks/a.md", "Task")),
                { "" },
                pendingPaths = setOf("другое/a.md", "tasks/a.md"),
                sync = SyncStatus.OFFLINE,
            )
        assertEquals(2, Regex("ещё не подтверждены GitHub").findAll(export).count())
        assertTrue(export.contains("нет связи с GitHub"))
    }

    @Test
    fun `selected project excludes unrelated source note and task`() {
        val source = Communication(title = "Selected", project = "A", text = "  raw\n````\n")
        val export =
            CommunicationExport.build(
                "A",
                listOf(source, source.copy(title = "Secret", project = "B")),
                listOf(
                    FeedItem(
                        "x",
                        null,
                        NoteFile.Note(
                            "другое/a.md",
                            mapOf("project" to "B", "title" to "OtherNote"),
                            "private",
                        ),
                    )
                ),
                listOf(TaskFile.Task("tasks/a.md", "OtherTask", project = "B")),
                { "" },
                "now",
            )
        assertTrue(export.contains(source.text))
        assertTrue(export.contains("`````"))
        assertFalse(export.contains("Secret"))
        assertFalse(export.contains("OtherNote"))
        assertFalse(export.contains("OtherTask"))
        assertFalse(export.contains("Ответственные"))
        assertTrue(export.contains("только на этом устройстве"))
    }

    @Test
    fun `explicit commitments and task dates remain attributable`() {
        val export =
            CommunicationExport.build(
                null,
                listOf(
                    Communication(
                        title = "Source",
                        text = "original",
                        commitments = "Review",
                        owners = "Alice",
                    )
                ),
                emptyList(),
                listOf(
                    TaskFile.Task("tasks/a.md", "Task", due = java.time.LocalDate.of(2026, 10, 10))
                ),
                { "" },
            )
        assertTrue(export.contains("Обещания (добавлены пользователем)"))
        assertTrue(export.contains("Alice"))
        assertTrue(export.contains("2026-10-10"))
        assertTrue(export.contains("Автоматической передачи помощнику нет"))
    }
}
