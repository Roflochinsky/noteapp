package com.roflochinsky.noteapp.pipeline

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NotesStoreFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `empty legacy delivery marker is not proof of successful upload`() {
        val dir = tmp.newFolder()
        assertNull(NotesStore.pushedPath(dir))
        val marker = File(dir, NotesStore.PUSHED)
        marker.writeText("")
        assertNull(NotesStore.pushedPath(dir))
        marker.writeText("inbox/2026-10-04-1200.md")
        assertEquals("inbox/2026-10-04-1200.md", NotesStore.pushedPath(dir))
    }

    @Test
    fun `atomic replacement writes complete UTF8 and leaves no temporary file`() {
        val target = tmp.newFile("transcript.md")
        target.writeText("старый текст")
        NotesStore.writeAtomic(target, "новый текст")
        assertEquals("новый текст", target.readText())
        assertFalse(File(target.parentFile, "transcript.md.tmp").exists())
    }

    @Test
    fun `failed atomic replacement preserves the previous destination`() {
        val target = tmp.newFolder("transcript.md")
        val existing = File(target, "keep.txt").apply { writeText("сохранить") }
        assertThrows(IOException::class.java) { NotesStore.writeAtomic(target, "новый текст") }
        assertEquals("сохранить", existing.readText())
    }
}
