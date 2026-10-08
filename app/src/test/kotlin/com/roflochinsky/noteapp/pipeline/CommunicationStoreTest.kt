package com.roflochinsky.noteapp.pipeline

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CommunicationStoreTest {
    @Test
    fun `save survives reopen and preserves exact source`() {
        val root = Files.createTempDirectory("communications").toFile()
        val original = "  Telegram export\n\nА: обещание\nБ: ответ  \n"
        val item = Communication(title = "Встреча", source = "Telegram / TGSUM", text = original)
        val saved = CommunicationStore(root).save(item)
        assertEquals(original, CommunicationStore(root).list().single().text)
        assertEquals(saved.id, CommunicationStore(root).list().single().id)
    }

    @Test
    fun `update replaces same id and blank title rejected`() {
        val root = Files.createTempDirectory("communications").toFile()
        val store = CommunicationStore(root)
        val saved = store.save(Communication(title = "One", text = "original"))
        store.save(saved.copy(title = "Two"))
        assertEquals("Two", store.list().single().title)
        assertThrows(IllegalArgumentException::class.java) { store.save(saved.copy(title = " ")) }
        assertEquals("Two", store.list().single().title)
    }

    @Test
    fun `broken source reports error rather than silently omitting it`() {
        val root = Files.createTempDirectory("communications").toFile()
        File(root, "broken.json").writeText("{bad")
        assertThrows(Exception::class.java) { CommunicationStore(root).list() }
    }

    @Test
    fun `source input is size bounded and ids cannot traverse`() {
        val store = CommunicationStore(Files.createTempDirectory("communications").toFile())
        assertThrows(IllegalArgumentException::class.java) {
            store.save(Communication(id = "../escape", title = "Title", text = "text"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.save(
                Communication(title = "Title", text = "x".repeat(CommunicationStore.MAX_TEXT + 1))
            )
        }
    }
}
