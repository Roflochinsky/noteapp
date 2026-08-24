package com.roflochinsky.noteapp

import org.junit.Assert.assertEquals
import org.junit.Test

class ProbeTest {
    @Test
    fun `пакет зонда совпадает со спекой`() {
        assertEquals("com.roflochinsky.noteapp", Probe.PACKAGE)
    }
}
