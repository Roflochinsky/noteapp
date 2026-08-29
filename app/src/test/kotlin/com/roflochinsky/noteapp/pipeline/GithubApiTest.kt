package com.roflochinsky.noteapp.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Поведение, которое живёт над портом, а не в адаптере: поиск обработанной заметки. Дерево читается
 * по SHA коммита (фейк на этом настаивает), необработанный `inbox/` пропускается.
 *
 * Долг Н-7 ревью Н1: раньше это была функция [GithubClient] с `readTree("main")` — фейком её было
 * не достать, и подстановка имени ветки вместо SHA работала случайно.
 */
class GithubApiTest {

    private val done = "встречи/2026-08-24-1807-reliz-tgsum.md"
    private val raw = "inbox/2026-08-24-1807.md"

    private fun api() =
        FakeGithubApi().apply {
            put(raw, "---\nstatus: raw\n---\n")
            put(done, "---\nstatus: done\n---\n")
        }

    @Test
    fun `обработанная заметка находится по префиксу имени`() {
        assertEquals(done, api().findDonePath("2026-08-24-1807"))
    }

    @Test
    fun `пока заметка лежит в inbox, обработанной она не считается`() {
        val api = FakeGithubApi().apply { put(raw, "---\nstatus: raw\n---\n") }
        assertNull(api.findDonePath("2026-08-24-1807"))
    }
}
