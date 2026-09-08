package com.roflochinsky.noteapp.pipeline

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Мост между файлами каталога записи и полями, которые читает экран. Робик нужен из-за
 * `context.filesDir`, а не из-за экрана.
 *
 * Мост этот в коде один и однострочный, а цена его пропажи — тихая и дорогая: сотрись строка про
 * [NotesStore.STATUS], и лента снова покажет всем одинаковое «в очереди — расшифровка», то есть
 * предмет всего среза выключится молча, при зелёном гейте.
 */
@RunWith(RobolectricTestRunner::class)
class NotesStoreTest {

    private val context = RuntimeEnvironment.getApplication()

    private fun record(id: String, status: String = ""): File =
        NotesStore.noteDir(context, id).also { dir ->
            File(dir, NotesStore.AUDIO).writeText("postanovochnye-bajty-zvuka")
            if (status.isNotEmpty()) File(dir, NotesStore.STATUS).writeText(status)
        }

    /**
     * Причина уезжает в запись ленты целиком, обеими строками: резать её на «первую для ленты» и
     * «всю для деталки» — работа шва `FeedItem` ([NoteRef]), а не хранилища.
     *
     * Записи без файла причины достаётся пусто — иначе «причина неизвестна, просто ждём очереди»
     * было бы не отличить от настоящей беды.
     */
    @Test
    fun `причина из файла приезжает в запись, а без файла причины нет`() {
        val reason = "ошибка ElevenLabs 401\n{\"detail\":\"invalid_api_key\"}"
        record("20260907-101500", status = reason)
        record("20260907-102000")

        val notes = NotesStore.list(context).associateBy { it.id }

        assertEquals(reason, notes.getValue("20260907-101500").status)
        assertEquals("", notes.getValue("20260907-102000").status)
    }
}
