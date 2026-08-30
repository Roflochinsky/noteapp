package com.roflochinsky.noteapp.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `новый проект дописывается строкой после последнего элемента`() {
        val was = fixture("projects.md")
        assertEquals(was + "- voicebox\n", Registry.add(was, "voicebox"))
    }

    /**
     * Второй такой же проект в реестре — это два разных значения чипа с одним смыслом. `null`
     * значит «писать нечего»: коммита не будет, а владелец получит уже существующее имя.
     */
    @Test
    fun `имя уже в реестре — писать нечего, регистр и пробелы не считаются`() {
        val was = fixture("projects.md")
        assertNull(Registry.add(was, "tgsum"))
        assertNull(Registry.add(was, "TGSum"))
        assertNull(Registry.add(was, "  noteapp  "))
        assertNull(Registry.add(was, "   "))
    }

    /**
     * Реестр правится и руками, и внизу файла может лежать пояснение. Новая строка встаёт в список,
     * а не под текст: приписанная в конец, она оторвалась бы от списка, который читают глазами в
     * GitHub.
     */
    @Test
    fun `строка встаёт в список, а не под текст в конце файла`() {
        val md =
            """
            # Проекты

            - tgsum — Telegram export → Markdown CLI
            - noteapp

            Список правится руками.
            """
                .trimIndent()
        assertEquals(
            listOf("tgsum", "noteapp", "voicebox"),
            Registry.names(Registry.add(md, "voicebox")),
        )
        assertTrue(Registry.add(md, "voicebox")!!.trimEnd().endsWith("Список правится руками."))
    }
}
