package com.roflochinsky.noteapp

import android.Manifest
import android.app.role.RoleManager
import com.roflochinsky.noteapp.pipeline.NotesStore
import com.roflochinsky.noteapp.pipeline.Settings
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * Два решения точки сборки, которые видны владельцу и не видны никакому другому тесту: кого гнать в
 * очередь после ввода ключа и когда онбординг считается пройденным.
 *
 * Сама `Activity` здесь не поднимается — оба решения вынесены в `companion` ровно затем, чтобы эти
 * строки исполнял тест (образец — `TranscribeWorker.transcribeById`). Что осталось непокрытым:
 * проводка «диалог сохранил ключ → [MainActivity.enqueueWaiting]» и «`onCreate` спросил
 * [MainActivity.setupComplete]» — объявленный долг среза, ловится прокликкой владельца.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityTest {

    private val context = RuntimeEnvironment.getApplication()

    /**
     * Флаг расшифровки и причина — параметры независимые, как в прод-коде: `transcribed` там
     * читается из наличия `transcript.md`, а причина из `status.txt`, и «расшифрована» вовсе не
     * значит «причины нет» (причина от фатального 401 остаётся лежать).
     */
    private fun record(id: String, transcribed: Boolean, status: String = "") {
        val dir = NotesStore.noteDir(context, id)
        File(dir, NotesStore.AUDIO).writeText("postanovochnye-bajty-zvuka")
        if (transcribed) File(dir, NotesStore.TRANSCRIPT_MD).writeText("[00:00] Спикер 1: текст")
        if (status.isNotEmpty()) File(dir, NotesStore.STATUS).writeText(status)
    }

    /**
     * Критерий приёмки 5, вторая половина: «после ввода ключа запись уходит сама». Владелец не
     * должен тапать по каждой заметке, копившейся без ключа, — иначе видимый статус только назовёт
     * беду, но не вылечит её.
     *
     * Уже расшифрованные записи в очередь не идут: цепочка кончается пушем, и лишний прогон стоит
     * лишнего похода в GitHub на каждую старую заметку.
     */
    @Test
    fun `после ввода ключа в очередь уходят ровно записи без транскрипта`() {
        record("20260907-101500", transcribed = false)
        record("20260907-102000", transcribed = true)
        record("20260907-103000", transcribed = false)
        val queued = mutableListOf<String>()

        MainActivity.enqueueWaiting(context, enqueue = queued::add, cancel = {})

        assertEquals(setOf("20260907-101500", "20260907-103000"), queued.toSet())
        assertEquals(queued.toString(), 2, queued.size)
    }

    /**
     * Критерий приёмки 5, вторая половина по-настоящему: «после ввода ключа запись уходит **сама**»
     * — а не через пять часов.
     *
     * Двух строк тут мало не бывает. Причина («нет ключа ElevenLabs») по построению протухла:
     * владелец только что сменил ключ, — и если её не снять, лента будет ругаться на ключ, который
     * уже введён. Висящая цепочка `note-<id>` не завершена (воркер вернул `retry`), а
     * `ExistingWorkPolicy.KEEP` ([PipelineQueue]) новый запрос при незавершённой работе выбрасывает
     * — значит без отмены постановка не делает ровно ничего, и момент расшифровки остаётся за
     * откатом, назначенным ещё ДО ввода ключа (потолок WorkManager — 5 часов).
     *
     * Порядок в списке — часть требования: отмени цепочку после постановки, и выброшено будет как
     * раз то, что поставили.
     */
    @Test
    fun `после ввода ключа протухшая причина снята, а висящая цепочка отменена до постановки`() {
        record("20260907-101500", transcribed = false, status = "нет ключа ElevenLabs")
        val log = mutableListOf<String>()

        MainActivity.enqueueWaiting(
            context,
            enqueue = { log += "в очередь $it" },
            cancel = { log += "отменить $it" },
        )

        assertFalse(
            "причина пережила ввод ключа — лента будет ругаться на введённый ключ",
            File(NotesStore.noteDir(context, "20260907-101500"), NotesStore.STATUS).exists(),
        )
        assertEquals(listOf("отменить 20260907-101500", "в очередь 20260907-101500"), log)
    }

    /**
     * Долг, перенесённый из среза 1: онбординг зелёный по ключу ПРЕЖНЕГО вендора — тихая и дорогая
     * ошибка. Ключ Deepgram спека велит не стирать (путь отката ADR), поэтому он в хранилище есть у
     * каждого владельца; сверься онбординг с ним — шага «Ключ ElevenLabs» владелец не увидел бы
     * вовсе, а каждая запись ушла бы в вечный `retry` при зелёном экране.
     */
    @Test
    fun `онбординг не считается пройденным по ключу прежнего вендора`() {
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO)
        shadowOf(context.getSystemService(RoleManager::class.java))
            .addHeldRole(RoleManager.ROLE_ASSISTANT)
        Settings.setGithubToken(context, "gh-token")
        Settings.setDeepgramKey(context, "dg-kluch")

        assertFalse(MainActivity.setupComplete(context))

        Settings.setElevenLabsKey(context, "xi-kluch")
        assertTrue(MainActivity.setupComplete(context))
    }
}
