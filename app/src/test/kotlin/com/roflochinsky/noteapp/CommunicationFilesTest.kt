package com.roflochinsky.noteapp

import android.net.Uri
import com.roflochinsky.noteapp.pipeline.Communication
import com.roflochinsky.noteapp.pipeline.CommunicationStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CommunicationFilesTest {
    @get:Rule val tmp = TemporaryFolder()
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `UTF8 import preserves original newlines and spaces`() {
        val file = tmp.newFile()
        file.writeText("  Автор: текст\n\n")
        assertEquals("  Автор: текст\n\n", CommunicationFiles.read(context, Uri.fromFile(file)))
    }

    @Test
    fun `invalid UTF8 reports actionable Russian error`() {
        val file = tmp.newFile()
        file.writeBytes(byteArrayOf(0xC3.toByte(), 0x28))
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                CommunicationFiles.read(context, Uri.fromFile(file))
            }
        assertTrue(error.message!!.contains("UTF-8"))
    }

    @Test
    fun `oversized file rejected without truncated import`() {
        val file = tmp.newFile()
        file.writeText("x".repeat(400_001))
        assertThrows(IllegalArgumentException::class.java) {
            CommunicationFiles.read(context, Uri.fromFile(file))
        }
    }

    @Test
    fun `local sources excluded from automatic Android backup`() {
        val value = Communication(title = "Synthetic source", text = "Synthetic text")
        CommunicationStore.onDevice(context).save(value)
        assertTrue(File(context.noBackupFilesDir, "communications/${value.id}.json").isFile)
        assertFalse(File(context.filesDir, "communications/${value.id}.json").exists())
    }

    @Test
    fun `shared context file contains only supplied content and provider is private`() {
        val file = CommunicationFiles.write(context, "Synthetic context")
        assertEquals("Synthetic context", file.readText())
        val provider =
            context.packageManager.resolveContentProvider(
                "${context.packageName}.context-files",
                0,
            )!!
        assertFalse(provider.exported)
        assertTrue(provider.grantUriPermissions)
    }
}
