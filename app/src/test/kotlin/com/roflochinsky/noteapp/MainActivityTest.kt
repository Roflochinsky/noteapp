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

    private fun record(id: String, transcribed: Boolean) {
        val dir = NotesStore.noteDir(context, id)
        File(dir, NotesStore.AUDIO).writeText("postanovochnye-bajty-zvuka")
        if (transcribed) File(dir, NotesStore.TRANSCRIPT_MD).writeText("[00:00] Спикер 1: текст")
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

        MainActivity.enqueueWaiting(context, queued::add)

        assertEquals(setOf("20260907-101500", "20260907-103000"), queued.toSet())
        assertEquals(queued.toString(), 2, queued.size)
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
