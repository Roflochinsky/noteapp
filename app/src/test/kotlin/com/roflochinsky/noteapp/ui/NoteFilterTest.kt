package com.roflochinsky.noteapp.ui

import com.roflochinsky.noteapp.pipeline.FeedItem
import com.roflochinsky.noteapp.pipeline.NoteFile
import com.roflochinsky.noteapp.pipeline.NoteRef
import com.roflochinsky.noteapp.pipeline.NotesStore
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Чипы ленты: тип, проект, персона, тег, дата — и поиск по заголовку и превью. */
class NoteFilterTest {

    private val today = LocalDate.of(2026, 8, 26)

    /** Заметка из репо строкой ленты: поля frontmatter картой — их у неё больше, чем аргументов. */
    private fun note(
        path: String,
        recorded: String,
        title: String,
        fields: Map<String, String> = emptyMap(),
        summary: String = "Суть записи",
    ): FeedItem {
        val md = buildString {
            append("---\nrecorded: $recorded\ntitle: $title\n")
            fields.forEach { (key, value) -> append("$key: $value\n") }
            append("status: done\n---\n\n## Саммари\n**Суть.** $summary\n")
        }
        val parsed = checkNotNull(NoteFile.parse(path, md))
        return FeedItem(NoteRef.of(parsed), null, parsed)
    }

    private val meeting =
        note(
            path = "встречи/2026-08-24-1807-reliz.md",
            recorded = "2026-08-24T18:07:32+03:00",
            title = "Созвон с Димой — релиз tgsum",
            fields =
                mapOf(
                    "type" to "встреча",
                    "project" to "tgsum",
                    "participants" to "[Дима, Никита]",
                    "tags" to "[релиз]",
                ),
            summary = "Релиз назначен на пятницу.",
        )

    private val idea =
        note(
            path = "идеи/2026-08-12-1922-notion.md",
            recorded = "2026-08-12T19:22:10+03:00",
            title = "Идея про экспорт в Notion",
            fields = mapOf("type" to "идея", "participants" to "[Дима]"),
            summary = "Экспорт прямо в Notion через их API.",
        )

    private val personal =
        note(
            path = "личное/2026-08-26-0900-vrach.md",
            recorded = "2026-08-26T09:00:00+03:00",
            title = "Записаться к врачу",
            fields = mapOf("type" to "личное"),
        )

    private val all = listOf(meeting, idea, personal)

    private fun titles(filter: NoteFilter.Filter) = filter.select(all, today).map { it.title }

    @Test
    fun `чип типа сужает ленту`() {
        assertEquals(listOf("Идея про экспорт в Notion"), titles(NoteFilter.Filter(type = "идея")))
    }

    @Test
    fun `чип проекта сужает ленту, а «без проекта» — обратное`() {
        assertEquals(listOf("Созвон с Димой — релиз tgsum"), titles(NoteFilter.Filter("tgsum")))
        assertEquals(2, NoteFilter.Filter(project = TaskFilter.NO_PROJECT).select(all, today).size)
    }

    @Test
    fun `чип персоны оставляет заметки этого человека`() {
        assertEquals(2, NoteFilter.Filter(person = "Дима").select(all, today).size)
        assertEquals(1, NoteFilter.Filter(person = "Никита").select(all, today).size)
    }

    @Test
    fun `чип тега сужает ленту`() {
        assertEquals(
            listOf("Созвон с Димой — релиз tgsum"),
            titles(NoteFilter.Filter(tag = "релиз")),
        )
    }

    @Test
    fun `чип даты меряет от сегодня`() {
        assertEquals(listOf("Записаться к врачу"), titles(NoteFilter.Filter(date = NoteFilter.DAY)))
        assertEquals(2, NoteFilter.Filter(date = NoteFilter.WEEK).select(all, today).size)
        assertEquals(3, NoteFilter.Filter(date = NoteFilter.MONTH).select(all, today).size)
    }

    /** Вторая половина решения владельца про поиск: по заголовку И по превью заметки. */
    @Test
    fun `поиск ищет и в заголовке, и в превью`() {
        assertEquals(
            listOf("Созвон с Димой — релиз tgsum"),
            titles(NoteFilter.Filter(query = "созвон")),
        )
        assertEquals(
            listOf("Идея про экспорт в Notion"),
            titles(NoteFilter.Filter(query = "через их api")),
        )
        assertTrue(titles(NoteFilter.Filter(query = "нет такого")).isEmpty())
    }

    @Test
    fun `чипы складываются между собой и с поиском`() {
        val both = NoteFilter.Filter(person = "Дима", type = "встреча")
        assertEquals(listOf("Созвон с Димой — релиз tgsum"), titles(both))
        assertTrue(titles(both.copy(query = "notion")).isEmpty())
    }

    @Test
    fun `запись без файла в репо под чипами не теряется, пока чипы не нажаты`() {
        val offline =
            FeedItem(
                "20260826-1200",
                NotesStore.Note(
                    "20260826-120000",
                    true,
                    true,
                    false,
                    30,
                    "Только что",
                    "Только что",
                ),
                null,
            )
        val feed = all + offline
        assertEquals(4, NoteFilter.Filter().select(feed, today).size)
        assertFalse(NoteFilter.Filter(type = "встреча").select(feed, today).contains(offline))
    }

    /**
     * Счётчики фасетные (вердикт UX): свой чип из расчёта исключён, чужие учитываются, значения
     * реестра без заметок остаются с нулём.
     */
    @Test
    fun `счётчики шторки фасетные`() {
        val filter = NoteFilter.Filter(type = "встреча")
        val counts =
            filter.counts(all, today, NoteFilter.Facet.TYPE, listOf("встреча", "идея", "личное"))
        assertEquals(3, counts[null])
        assertEquals(1, counts["встреча"])
        assertEquals(1, counts["идея"])

        val people = filter.counts(all, today, NoteFilter.Facet.PERSON, listOf("Дима", "Оля"))
        assertEquals("чужой чип «тип» учтён", 1, people["Дима"])
        assertEquals("персоны без заметок остаются с нулём", 0, people["Оля"])
    }

    @Test
    fun `сброшенный фильтр не считается активным`() {
        assertFalse(NoteFilter.Filter().active)
        assertTrue(NoteFilter.Filter(tag = "релиз").active)
        assertTrue(NoteFilter.Filter(query = "созвон").active)
    }
}
