package com.roflochinsky.noteapp.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Реестры `projects.md` / `people.md` — настоящие файлы тестового репо `voice-notes-test`, снятые
 * побайтово (`app/src/test/resources/repo/`, git-блобы `385bc05…` и `5217bc1…`). Сочинять их
 * нельзя: формат реестра — часть ADR `2026-08-26-tasks-as-files.md`.
 */
class RegistryTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/repo/$name")) { "нет фикстуры $name" }.readText()

    @Test
    fun `имена проектов берутся из настоящего projects_md`() {
        assertEquals(
            listOf("tgsum", "workwatch", "noteapp"),
            Registry.names(fixture("projects.md")),
        )
    }

    @Test
    fun `имена персон берутся из настоящего people_md`() {
        assertEquals(listOf("Дима", "Никита", "Оля"), Registry.names(fixture("people.md")))
    }

    @Test
    fun `пояснение после тире в имя не попадает, заголовок и мусор пропускаются`() {
        val md =
            """
            # Проекты

            Список проектов, правится руками.

            - tgsum — Telegram export → Markdown CLI
            * noteapp
              -  с пробелами  — и пояснением
            """
                .trimIndent()
        assertEquals(listOf("tgsum", "noteapp", "с пробелами"), Registry.names(md))
    }

    @Test
    fun `реестра нет — список пуст, а не исключение`() {
        assertEquals(emptyList<String>(), Registry.names(null))
    }
}
