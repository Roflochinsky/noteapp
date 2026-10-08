package com.roflochinsky.noteapp

import android.content.Intent
import com.roflochinsky.noteapp.pipeline.CommunicationStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CommunicationIntentsTest {
    @Test
    fun `cold oversized share retains actionable error and never saves`() {
        val intent =
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "x".repeat(CommunicationStore.MAX_TEXT + 1))
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).create()
        val activity = controller.get()
        assertTrue(activity.communicationController.error.orEmpty().contains("слишком большой"))
        assertTrue(CommunicationStore.onDevice(activity).list().isEmpty())
        assertNull(activity.intent.action)
        controller.destroy()
    }

    @Test
    fun `shared source requires explicit save and is consumed only once`() {
        val intent =
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "  Synthetic source\n")
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).create()
        val activity = controller.get()
        assertEquals(emptyList<Any>(), CommunicationStore.onDevice(activity).list())
        assertNull(activity.intent.action)
        controller.destroy()
    }
}
