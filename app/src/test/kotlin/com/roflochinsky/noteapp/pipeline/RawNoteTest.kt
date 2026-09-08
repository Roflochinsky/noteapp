package com.roflochinsky.noteapp.pipeline

import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Формат raw-заметки — строго по docs/specs/2026-08-24-note-format.md. */
class RawNoteTest {

    @Test
    fun `frontmatter содержит все поля формата`() {
        val md =
            RawNote.build(
                RawNote.Input(
                    noteId = "20260824-180732",
                    zone = ZoneOffset.ofHours(3),
                    durationSec = 751,
                    device = "OnePlus 13",
                    transcriptMd = "[00:12] Спикер 1: привет",
                )
            )
        assertTrue(md.startsWith("---\n"))
        assertTrue(md.contains("recorded: 2026-08-24T18:07:32+03:00"))
        assertTrue(md.contains("duration: 12:31"))
        assertTrue(md.contains("device: OnePlus 13"))
        assertTrue(md.contains("status: raw"))
        assertTrue(md.contains("## Транскрипт\n\n[00:12] Спикер 1: привет"))
    }

    @Test
    fun `моменты пишутся секцией только когда есть`() {
        val with =
            RawNote.build(
                RawNote.Input("20260824-180732", ZoneOffset.UTC, 60, "d", listOf(31_000L), "t")
            )
        assertTrue(with.contains("## Моменты\n\n- [00:31]"))
        val without =
            RawNote.build(
                RawNote.Input("20260824-180732", ZoneOffset.UTC, 60, "d", emptyList(), "t")
            )
        assertFalse(without.contains("## Моменты"))
    }

    /**
     * Имя теста называет ровно то, что он видит, и не больше. Видит он одну вещь: сборка заметки
     * печатает поля [RawNote.Input] и НИ СТРОКИ сверх них. Сверка идёт со всей строкой, а не по
     * `contains`: любая приписка сборки валит тест, а `contains` её бы не заметил.
     *
     * Зачем это здесь. Файл причины (`status.txt`) лежит в каталоге записи рядом с аудио и
     * транскриптом, и в репо заметок уезжать не должен: владельцу там нужна заметка, а не служебная
     * строка телефона. Но каталога записи эта сборка не видит вовсе, поэтому назвать тест «файл
     * причины не уезжает в GitHub» было бы обещанием шире мерила — двух путей утечки он не
     * закрывает:
     * - как [PushWorker] набирает `Input` из каталога записи (`PushWorker.kt:37-47`) — собрать
     *   `CoroutineWorker` в юните нечем, `androidx.work:work-testing` в зависимостях нет;
     * - новое НЕОБЯЗАТЕЛЬНОЕ поле `Input`, печатаемое по условию: образец такого поля — `##
     *   Моменты` рядом, и на этом входе он невидим.
     */
    @Test
    fun `сборка заметки печатает ровно поля Input и ни строки сверх них`() {
        val md =
            RawNote.build(
                RawNote.Input(
                    noteId = "20260824-180732",
                    zone = ZoneOffset.ofHours(3),
                    durationSec = 751,
                    device = "OnePlus 13",
                    transcriptMd = "[00:12] Спикер 1: привет",
                )
            )
        assertEquals(
            """
            ---
            recorded: 2026-08-24T18:07:32+03:00
            duration: 12:31
            device: OnePlus 13
            status: raw
            ---

            ## Транскрипт

            [00:12] Спикер 1: привет
            """
                .trimIndent() + "\n",
            md,
        )
    }

    @Test
    fun `имя файла - дата-время без секунд`() {
        assertEquals("2026-08-24-1807.md", RawNote.fileName("20260824-180732"))
    }
}
