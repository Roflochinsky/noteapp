package com.roflochinsky.noteapp.pipeline

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тождество «локальная запись ↔ файл в репо» (решение LLD-10) и склейка ленты (решение LLD-11).
 *
 * Фикстуры настоящие: тексты заметок — `app/src/test/resources/repo/`, пара имён до и после
 * переименования Action-ом — из снятого с живого GitHub `compare-ahead.json` (в нём есть статус
 * `renamed` с `previous_filename`). Сочинять их запрещено (граница автономии плана).
 */
class NoteRefTest {

    private fun fixture(name: String) =
        checkNotNull(javaClass.getResource("/repo/$name")) { "нет фикстуры $name" }.readText()

    private val vtwo = fixture("note-v2-links.md") // recorded 2026-08-24T18:07:32+03:00
    private val vone = fixture("note-v1-checkbox.md") // recorded 2026-08-12T19:22:10+03:00

    private fun note(path: String, md: String) = checkNotNull(NoteFile.parse(path, md))

    private fun record(id: String, pushed: Boolean = true) =
        NotesStore.Note(
            id = id,
            hasAudio = true,
            transcribed = true,
            pushed = pushed,
            durationSec = 751,
            title = "Смотри, по релизу",
            preview = "Смотри, по релизу",
        )

    /** Пара имён того же файла до и после переименования — из ответа `compare` живого репо. */
    private fun renamed(): Pair<String, String> {
        val json =
            JSONObject(checkNotNull(javaClass.getResource("/github/compare-ahead.json")).readText())
        val files = json.getJSONArray("files")
        val renamed =
            (0 until files.length())
                .map { files.getJSONObject(it) }
                .single { it.getString("status") == "renamed" }
        return renamed.getString("previous_filename") to renamed.getString("filename")
    }

    @Test
    fun `запись и её файл в репо дают один ref`() {
        val fromRecord = NoteRef.of("20260824-180732")
        val fromRepo = NoteRef.of(note("встречи/2026-08-24-1807-reliz-tgsum.md", vtwo))
        assertEquals(fromRecord, fromRepo)
    }

    @Test
    fun `переименование Action-ом ref не меняет`() {
        val (before, after) = renamed()
        assertEquals(NoteRef.of(note(before, vone)), NoteRef.of(note(after, vone)))
    }

    /** Имя файла — запасное тождество: `recorded` из frontmatter точнее и идёт первым. */
    @Test
    fun `без recorded ref берётся из имени файла`() {
        val noHead = vtwo.replace("recorded: 2026-08-24T18:07:32+03:00\n", "")
        assertEquals(
            NoteRef.of("20260824-180700"),
            NoteRef.of(note("встречи/2026-08-24-1807-reliz-tgsum.md", noHead)),
        )
    }

    @Test
    fun `заметки — только папки типов и inbox`() {
        assertTrue(NoteRef.isNote("встречи/2026-08-24-1807-reliz-tgsum.md"))
        assertTrue(NoteRef.isNote("inbox/2026-08-24-1807.md"))
        assertEquals(false, NoteRef.isNote("tasks/2026-08-25-fix-retraev-ocheredi.md"))
        assertEquals(false, NoteRef.isNote("people.md"))
        assertEquals(false, NoteRef.isNote("встречи/2026/вложенная.md"))
    }

    /** Решение LLD-7: пока заметка в `inbox/`, её обрабатывает Action — поля не правим. */
    @Test
    fun `заметка из inbox не правится`() {
        assertEquals(false, NoteRef.isEditable("inbox/2026-08-24-1807.md"))
        assertTrue(NoteRef.isEditable("встречи/2026-08-24-1807-reliz-tgsum.md"))
        assertEquals(false, NoteRef.isEditable("tasks/2026-08-25-fix.md"))
    }

    @Test
    fun `локальная запись и её файл в репо — одна строка ленты`() {
        val feed =
            NoteRef.merge(
                listOf(record("20260824-180732")),
                listOf(note("встречи/2026-08-24-1807-reliz-tgsum.md", vtwo)),
            )
        assertEquals(1, feed.size)
        assertEquals("Созвон с Димой — релиз tgsum", feed[0].title)
        assertEquals("встречи/2026-08-24-1807-reliz-tgsum.md", feed[0].path)
        assertEquals("20260824-180732", feed[0].noteId)
    }

    /** Принцип 4: запись без сети видна в ленте и не двоится, когда файл доедет. */
    @Test
    fun `запись без сети видна в ленте одной строкой`() {
        val local = listOf(record("20260824-180732", pushed = false))
        val offline = NoteRef.merge(local, emptyList())
        assertEquals(1, offline.size)
        assertNull(offline[0].path)
        assertEquals(false, offline[0].pushed)

        val pushed = NoteRef.merge(local, listOf(note("встречи/2026-08-24-1807-r.md", vtwo)))
        assertEquals(1, pushed.size)
        assertTrue(pushed[0].pushed)
    }

    @Test
    fun `заметка из репо без локальной записи в ленте есть`() {
        val feed = NoteRef.merge(emptyList(), listOf(note("идеи/2026-08-12-1922-notion.md", vone)))
        assertEquals(listOf("Дима — идея про экспорт в Notion"), feed.map { it.title })
        assertNull(feed[0].noteId)
    }

    @Test
    fun `лента идёт сверху вниз от свежего`() {
        val feed =
            NoteRef.merge(
                listOf(record("20260824-180732")),
                listOf(note("идеи/2026-08-12-1922-notion.md", vone)),
            )
        assertEquals(listOf("20260824-1807", "20260812-1922"), feed.map { it.ref })
    }

    @Test
    fun `поля заметки видны в строке ленты`() {
        val feed = NoteRef.merge(emptyList(), listOf(note("встречи/2026-08-24-1807-r.md", vtwo)))
        val item = feed.single()
        assertEquals("встреча", item.type)
        assertEquals("tgsum", item.project)
        assertEquals(listOf("Дима", "Никита"), item.participants)
        assertEquals(listOf("релиз"), item.tags)
        assertEquals(751, item.durationSec)
        assertTrue(item.preview.startsWith("Релиз tgsum v0.2 назначен на пятницу"))
    }
}
