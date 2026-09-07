package com.roflochinsky.noteapp.pipeline

import androidx.work.ListenableWorker
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Решение воркера по одной заметке: что кладём рядом с записью и когда повторяем запрос.
 *
 * Проверяется без WorkManager — [TranscribeWorker.transcribeNote] это и есть всё решение по
 * каталогу записи, а [TranscribeWorker.transcribeById] вокруг него выбирает каталог, ключ и вендора
 * (`doWork` после этого только достаёт `noteId` из `Data`). Робик нужен из-за `android.util.Log` в
 * пробах и из-за настроек, а не из-за экрана.
 *
 * Цена вопроса: одна запись — один запрос на 48 МБ. Поэтому «повторить» и «сдаться» здесь разделены
 * по коду ответа, а не свалены в один `catch`.
 */
@RunWith(RobolectricTestRunner::class)
class TranscribeWorkerTest {

    @get:Rule val tmp = TemporaryFolder()

    private var calls = 0

    private val sample =
        requireNotNull(javaClass.classLoader?.getResource("elevenlabs-sample-response.json")) {
                "нет фикстуры"
            }
            .readText()

    private fun noteDir(
        name: String = "20260907-101500",
        transcribed: Boolean = false,
        withAudio: Boolean = true,
    ): File {
        val dir = tmp.newFolder(name)
        if (withAudio) File(dir, NotesStore.AUDIO).writeText("postanovochnye-bajty-zvuka")
        if (transcribed) File(dir, NotesStore.TRANSCRIPT_MD).writeText(OLD_MD)
        return dir
    }

    private fun run(
        dir: File,
        key: String? = "xi-test-kluch",
        stt: (File, String) -> String = { _, _ -> sample },
    ): ListenableWorker.Result =
        TranscribeWorker.transcribeNote(dir, "20260907-101500", key) { audio, apiKey ->
            calls++
            stt(audio, apiKey)
        }

    /**
     * Критерий приёмки 1. `transcript.json` — ответ вендора **целиком и побайтно**: его читают
     * будущие срезы (прокрут по словам), и любая «нормализация» по дороге ломает их молча. «Заметка
     * доехала до репо заметок» тестом не держится — это прокликка владельца (решение Р8).
     */
    @Test
    fun `ответ ложится рядом с записью целиком, реплики — в markdown`() {
        val dir = noteDir()
        assertEquals(ListenableWorker.Result.success(), run(dir) { _, _ -> sample })

        assertEquals(sample, File(dir, NotesStore.TRANSCRIPT_JSON).readText())
        val md = File(dir, NotesStore.TRANSCRIPT_MD).readText()
        assertTrue(md, md.startsWith("[00:01] Спикер 1: Собрали новый релиз"))
        assertEquals(8, md.lines().size)
    }

    /**
     * 400/401/403 — ключ, права или сам запрос сами не починятся; повтор стоит ещё 48 МБ и ничего
     * не даёт. Кода три, а не один: ключ без права Speech to Text отвечает **403**, и уход по нему
     * в `retry` означал бы бесконечные повторы по 48 МБ на часовой записи.
     */
    @Test
    fun `отказ по ключу или запросу — failure, запись не перезапрашивается`() {
        listOf(400, 401, 403).forEach { code ->
            val dir = noteDir(name = "note-$code")
            val result =
                run(dir) { _, _ -> throw SttError(code, """{"detail":"invalid_api_key"}""") }
            assertEquals("код $code", ListenableWorker.Result.failure(), result)
            assertFalse("код $code", File(dir, NotesStore.TRANSCRIPT_MD).exists())
        }
    }

    /** 429 и 5xx — вендор просит подождать, а не сдаться. */
    @Test
    fun `перегрузка и сбой вендора — retry`() {
        listOf(429, 500, 503).forEach { code ->
            val dir = tmp.newFolder("note-$code")
            File(dir, NotesStore.AUDIO).writeText("postanovochnye-bajty-zvuka")
            val result =
                TranscribeWorker.transcribeNote(dir, "note-$code", "xi-test-kluch") { _, _ ->
                    throw SttError(code, "busy")
                }
            assertEquals("код $code", ListenableWorker.Result.retry(), result)
        }
    }

    @Test
    fun `сетевой сбой — retry`() {
        val dir = noteDir()
        val result = run(dir) { _, _ -> throw IOException("сеть моргнула") }
        assertEquals(ListenableWorker.Result.retry(), result)
        assertFalse(File(dir, NotesStore.TRANSCRIPT_MD).exists())
    }

    /**
     * Ключа нет — `retry`, а не `failure`: запись обязана остаться в очереди и уйти сама после
     * ввода ключа. `failure` вычеркнул бы её навсегда, а видимый статус приезжает срезом `u4g.2`.
     */
    @Test
    fun `нет ключа — retry и ни одного запроса`() {
        val dir = noteDir()
        assertEquals(ListenableWorker.Result.retry(), run(dir, key = null))
        assertEquals(0, calls)
        assertFalse(File(dir, NotesStore.TRANSCRIPT_MD).exists())
    }

    /**
     * Бюджет спеки «ровно 1 запрос на запись» — это деньги. Готовый `transcript.md` значит, что
     * заметка уже расшифрована: второй запрос не уходит, и разметка не переписывается.
     */
    @Test
    fun `готовый transcript md — второго запроса нет`() {
        val dir = noteDir(transcribed = true)
        assertEquals(ListenableWorker.Result.success(), run(dir))
        assertEquals(0, calls)
        assertEquals(OLD_MD, File(dir, NotesStore.TRANSCRIPT_MD).readText())
    }

    /**
     * Обратная сторона того же бюджета: удачная расшифровка стоит **ровно один** запрос. Сверок с
     * нулём для этого мало — счётчик, считающий вдвое, они не отличают от честного.
     */
    @Test
    fun `удачная расшифровка — ровно один запрос`() {
        assertEquals(ListenableWorker.Result.success(), run(noteDir()))
        assertEquals(1, calls)
    }

    /**
     * Чей ключ воркер берёт **сам**, без подсказки параметром: в остальных тестах ключ приезжает
     * аргументом, и строка выбора настройки не исполняется вовсе. Владелец ввёл только ключ
     * ElevenLabs, ключ прежнего вендора намеренно пуст — подмена настройки здесь тиха и дорога:
     * каждая запись ушла бы в вечный `retry` без единой расшифровки при зелёном гейте.
     */
    @Test
    fun `ключ берётся из настройки ElevenLabs, а не из настройки прежнего вендора`() {
        val context = RuntimeEnvironment.getApplication()
        Settings.setElevenLabsKey(context, "xi-test-kluch")
        val dir = NotesStore.noteDir(context, "20260907-101500")
        File(dir, NotesStore.AUDIO).writeText("postanovochnye-bajty-zvuka")
        val seen = mutableListOf<String>()

        val result =
            TranscribeWorker.transcribeById(context, "20260907-101500") { _, key ->
                seen += key
                sample
            }

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(listOf("xi-test-kluch"), seen)
        assertTrue(File(dir, NotesStore.TRANSCRIPT_MD).exists())
    }

    /** Записи нет — расшифровывать нечего, и повтор ничего не изменит. */
    @Test
    fun `нет аудио — failure`() {
        assertEquals(ListenableWorker.Result.failure(), run(noteDir(withAudio = false)))
        assertEquals(0, calls)
    }

    private companion object {
        const val OLD_MD = "[00:00] Спикер 1: старая заметка"
    }
}
