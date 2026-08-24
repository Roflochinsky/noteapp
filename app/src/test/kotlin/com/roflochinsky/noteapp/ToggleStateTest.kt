package com.roflochinsky.noteapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Контракт тумблера — вердикт LLD-1 плана: истина в сервисе, решения идемпотентны. */
class ToggleStateTest {

    @Test
    fun `из простоя toggle начинает запись`() {
        val state = ToggleState()
        assertEquals(ToggleDecision.START, state.toggle())
        assertTrue(state.recording)
    }

    @Test
    fun `во время записи toggle останавливает её`() {
        val state = ToggleState()
        state.toggle()
        assertEquals(ToggleDecision.STOP, state.toggle())
        assertFalse(state.recording)
    }

    @Test
    fun `два toggle подряд дают start и stop а не двойной start`() {
        val state = ToggleState()
        val decisions = listOf(state.toggle(), state.toggle())
        assertEquals(listOf(ToggleDecision.START, ToggleDecision.STOP), decisions)
    }

    @Test
    fun `stop при простое — no-op`() {
        val state = ToggleState()
        assertEquals(ToggleDecision.NOOP, state.stop())
        assertFalse(state.recording)
    }

    @Test
    fun `stop во время записи останавливает`() {
        val state = ToggleState()
        state.toggle()
        assertEquals(ToggleDecision.STOP, state.stop())
        assertFalse(state.recording)
    }
}
