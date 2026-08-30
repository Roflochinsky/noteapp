package com.roflochinsky.noteapp.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тождество «локальная запись ↔ файл в репо» (решение LLD-10) и склейка ленты (решение LLD-11).
 *
 * Фикстуры настоящие: тексты заметок — `app/src/test/resources/repo/`, пара «до и после
 * переименования Action-ом» — эталонная пара `app/src/test/resources/action/` (raw-вход от телефона
 * и done-результат промпта). Сочинять их запрещено (граница автономии плана).
 *
 * Пара `renamed` из `compare-ahead.json` для этого не годится и убрана из теста: в ней времени нет
 * НИ В ОДНОМ из двух имён, поэтому обе стороны сводились к одному и тому же тексту и тест проходил
 * при любой реализации (замечание ревью Н5).
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

    /**
     * Конечный путь заметки после переноса Action-ом — не из головы: его называет `source:`
     * соседнего файла задачи той же эталонной пары.
     */
    private fun movedTo(): String =
        checkNotNull(
            TaskFile.parse(
                    "tasks/2026-08-26-podnyat-limit-retraev.md",
                    ActionFixture.text("2026-08-26-podnyat-limit-retraev.md"),
                )
                .source
        )

    @Test
    fun `запись и её файл в репо дают один ref`() {
        val fromRecord = NoteRef.of("20260824-180732")
        val fromRepo = NoteRef.of(note("встречи/2026-08-24-1807-reliz-tgsum.md", vtwo))
        assertEquals(fromRecord, fromRepo)
    }

    /**
     * Настоящее переименование Action-ом: `inbox/2026-08-26-0914.md` со `status: raw` уезжает в
     * папку типа под слагом, и вместе с путём меняются тип, участники, проект, теги, заголовок и
     * статус. Ref обязан пережить всё это разом — сравниваются РАЗНЫЕ тексты под РАЗНЫМИ путями.
     */
    @Test
    fun `переименование Action-ом ref не меняет`() {
        val before = note("inbox/2026-08-26-0914.md", ActionFixture.text("note-raw-with-tasks.md"))
        val after = note(movedTo(), ActionFixture.text("note-done-with-tasks.md"))
        assertEquals("встречи/2026-08-26-0914-limity-retraev.md", after.path)
        assertEquals("20260826-0914", NoteRef.of(before))
        assertEquals(NoteRef.of(before), NoteRef.of(after))
    }

    /**
     * `recorded` бьёт имя файла, и это не вкусовщина: промпт Action прямо запрещает трогать
     * `recorded` («Поле recorded, duration, device не трогай»), а имя файла он как раз переписывает
     * — переносит в папку типа и дописывает слаг. Значит на расхождении прав frontmatter от
     * телефона, а не время, оставшееся в имени. Проверяется расхождением: `recorded` 18:07, в имени
     * 18:10.
     */
    @Test
    fun `имя файла разошлось с recorded — верен recorded`() {
        val moved = note("встречи/2026-08-24-1810-reliz-tgsum.md", vtwo)
        assertEquals("20260824-1807", NoteRef.of(moved))
        val feed = NoteRef.merge(listOf(record("20260824-180732")), listOf(moved))
        assertEquals("запись и её файл разъехались бы на две строки", 1, feed.size)
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

    /**
     * Блокер ревью Б2: `associateBy` по ref схлопывал две заметки одной минуты, и первая молча
     * исчезала из ленты. Минута — предел ТОЖДЕСТВА, но не предел репо: Action переносит такие
     * заметки под разными слагами в разные папки типов, файлы у них разные. «Ошибка не теряет
     * запись» — значит обе строки на месте.
     */
    @Test
    fun `две заметки репо в одну минуту дают две строки`() {
        val feed =
            NoteRef.merge(
                emptyList(),
                listOf(
                    note("встречи/2026-08-24-1807-reliz-tgsum.md", vtwo),
                    note("идеи/2026-08-24-1807-eksport.md", vtwo),
                ),
            )
        assertEquals(
            listOf("встречи/2026-08-24-1807-reliz-tgsum.md", "идеи/2026-08-24-1807-eksport.md"),
            feed.mapNotNull { it.path }.sorted(),
        )
    }

    /** Уникален не ref (он общий у минуты), а ключ строки: путь файла либо id записи. */
    @Test
    fun `ключи строк ленты уникальны, даже когда ref общий`() {
        val feed =
            NoteRef.merge(
                listOf(record("20260824-180705"), record("20260824-180741")),
                listOf(
                    note("встречи/2026-08-24-1807-reliz-tgsum.md", vtwo),
                    note("идеи/2026-08-24-1807-eksport.md", vtwo),
                ),
            )
        assertEquals(listOf("20260824-1807"), feed.map { it.ref }.distinct())
        assertEquals(feed.size, feed.map { it.key }.distinct().size)
    }

    /**
     * Одна минута, но записей и файлов поровну: склейка разводит их парами, а не вешает один и тот
     * же файл на обе строки — иначе владелец видит одну заметку дважды.
     */
    @Test
    fun `записи одной минуты разбираются по файлам по одному`() {
        val feed =
            NoteRef.merge(
                listOf(record("20260824-180705"), record("20260824-180741")),
                listOf(
                    note("встречи/2026-08-24-1807-reliz-tgsum.md", vtwo),
                    note("идеи/2026-08-24-1807-eksport.md", vtwo),
                ),
            )
        assertEquals(2, feed.size)
        assertEquals(2, feed.mapNotNull { it.path }.distinct().size)
        assertEquals(2, feed.mapNotNull { it.noteId }.distinct().size)
    }

    /**
     * П2 ревью: список записей идёт от свежего, поэтому неотправленная запись 18:07:41 шла первой и
     * забирала файл, приехавший от записи 18:07:05. Владелец видел на своей записи, лежащей в
     * очереди, чужой заголовок, чужие поля и «✓ в GitHub». Файла у неотправленной записи в репо
     * быть НЕ МОЖЕТ — это и есть сигнал, по которому пара разбирается.
     */
    @Test
    fun `неотправленная запись не забирает чужой файл`() {
        val feed =
            NoteRef.merge(
                listOf(record("20260824-180741", pushed = false), record("20260824-180705")),
                listOf(note("встречи/2026-08-24-1807-reliz-tgsum.md", vtwo)),
            )
        val queued = feed.single { it.noteId == "20260824-180741" }
        assertNull("файл принадлежит записи 18:07:05", queued.path)
        assertEquals(false, queued.pushed)
        assertEquals("Смотри, по релизу", queued.title)
        val sent = feed.single { it.noteId == "20260824-180705" }
        assertEquals("встречи/2026-08-24-1807-reliz-tgsum.md", sent.path)
    }

    /**
     * Обратная сторона того же разбора: `pushed` задаёт ПОРЯДОК разбора, а не запрет. Флажок
     * `pushed.txt` теряется при переустановке приложения — если бы неотправленная запись файл брать
     * не смела вовсе, файл остался бы сиротой и стал третьей строкой, то есть двойником одной из
     * записей. Файл достаётся кому-то всегда.
     */
    @Test
    fun `потерянный флажок отправки строку не раздваивает`() {
        val feed =
            NoteRef.merge(
                listOf(
                    record("20260824-180741", pushed = false),
                    record("20260824-180705", pushed = false),
                ),
                listOf(note("встречи/2026-08-24-1807-reliz-tgsum.md", vtwo)),
            )
        assertEquals(2, feed.size)
        assertEquals(1, feed.count { it.path != null })
        assertEquals(2, feed.mapNotNull { it.noteId }.distinct().size)
    }

    /**
     * Какой файл достаётся какой записи — закреплено, а не «как ляжет»: записи разбирают файлы в
     * порядке ленты, файлы — в порядке репо. Пока это не закреплено, разбор можно перевернуть и
     * гейт останется зелёным (выжившая мутация ревью), а владелец получит перепутанные заголовки.
     */
    @Test
    fun `разбор пар одной минуты детерминирован`() {
        val feed =
            NoteRef.merge(
                listOf(record("20260824-180741"), record("20260824-180705")),
                listOf(
                    note("встречи/2026-08-24-1807-reliz-tgsum.md", vtwo),
                    note("идеи/2026-08-24-1807-eksport.md", vtwo),
                ),
            )
        assertEquals(
            mapOf(
                "20260824-180741" to "встречи/2026-08-24-1807-reliz-tgsum.md",
                "20260824-180705" to "идеи/2026-08-24-1807-eksport.md",
            ),
            feed.associate { it.noteId to it.path },
        )
    }

    /**
     * Ключ склеенной строки — id записи, и приоритет в [FeedItem.key] закреплён именно этим:
     * переверни его на «путь файла первым» — и переименование Action-ом сменит ключ строки, то есть
     * выкинет владельца с открытой деталки обратно в ленту. Проверяется настоящей парой «до и после
     * переноса»: путь меняется, ключ обязан остаться.
     */
    @Test
    fun `переименование Action-ом ключ склеенной строки не меняет`() {
        val local = listOf(record("20260826-091405"))
        val before =
            NoteRef.merge(
                    local,
                    listOf(
                        note(
                            "inbox/2026-08-26-0914.md",
                            ActionFixture.text("note-raw-with-tasks.md"),
                        )
                    ),
                )
                .single()
        val after =
            NoteRef.merge(
                    local,
                    listOf(note(movedTo(), ActionFixture.text("note-done-with-tasks.md"))),
                )
                .single()
        assertEquals("inbox/2026-08-26-0914.md", before.path)
        assertEquals("встречи/2026-08-26-0914-limity-retraev.md", after.path)
        assertEquals("20260826-091405", after.key)
        assertEquals(before.key, after.key)
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
