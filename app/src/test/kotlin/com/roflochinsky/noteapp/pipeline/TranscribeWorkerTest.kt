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

    /** 408, 429 и 5xx — вендор просит подождать, а не сдаться. */
    @Test
    fun `перегрузка и сбой вендора — retry`() {
        listOf(408, 429, 500, 503).forEach { code ->
            val dir = tmp.newFolder("note-$code")
            File(dir, NotesStore.AUDIO).writeText("postanovochnye-bajty-zvuka")
            val result =
                TranscribeWorker.transcribeNote(dir, "note-$code", "xi-test-kluch") { _, _ ->
                    throw SttError(code, "busy")
                }
            assertEquals("код $code", ListenableWorker.Result.retry(), result)
        }
    }

    /**
     * Сеть моргнула — заметка молча ждёт дальше. Заодно снимается устаревшая причина: владелец уже
     * ввёл ключ, а строка «нет ключа ElevenLabs» пережила бы попытку и врала бы ему в ленте.
     */
    @Test
    fun `сетевой сбой — retry, устаревшая причина снята`() {
        val dir = noteDir()
        File(dir, NotesStore.STATUS).writeText(NO_KEY)
        val result = run(dir) { _, _ -> throw IOException("сеть моргнула") }
        assertEquals(ListenableWorker.Result.retry(), result)
        assertFalse(File(dir, NotesStore.TRANSCRIPT_MD).exists())
        assertFalse(File(dir, NotesStore.STATUS).exists())
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

    /**
     * Критерий приёмки 5. Без ключа заметка не пропадает молча: рядом с записью ложится причина
     * словами, и лента показывает её вместо общего «в очереди — расшифровка».
     *
     * Судьба записи здесь намеренно НЕ проверяется — за «осталась в очереди» отвечает тест выше.
     * Свали их в один, и одна подмена валила бы сразу двух стражей: по падению уже не понять,
     * потеряли мы запись или перестали объяснять владельцу, почему она стоит.
     */
    @Test
    fun `нет ключа — причина видна словами`() {
        val dir = noteDir()
        run(dir, key = null)
        assertEquals(listOf(NO_KEY), File(dir, NotesStore.STATUS).readLines())
    }

    /**
     * Критерий приёмки 6. Вендор отказал — владелец видит код и начало тела ответа: без кода «ключ
     * с опечаткой» (401) и «ключ без права Speech to Text» (403) выглядят одинаково. Первая строка
     * — для ленты, вторая — для плашки деталки.
     */
    @Test
    fun `отказ вендора — в причине код и начало тела ответа`() {
        val dir = noteDir()
        run(dir) { _, _ -> throw SttError(401, """{"detail":"invalid_api_key"}""") }
        assertEquals(
            listOf("ошибка ElevenLabs 401", """{"detail":"invalid_api_key"}"""),
            File(dir, NotesStore.STATUS).readLines(),
        )
    }

    /**
     * Решение ведущего по находке П4 ревью среза 1: неисправимы **все** 4xx, кроме 408 и 429. До
     * него фатальными были ровно 400/401/403, поэтому 404 (не тот путь) и 422 (не то тело) уходили
     * в бесконечные повторы по 48 МБ на каждой часовой записи.
     */
    @Test
    fun `прочие 4xx — тоже failure, а не вечные повторы`() {
        listOf(404, 422).forEach { code ->
            val dir = noteDir(name = "note-$code")
            assertEquals(
                "код $code",
                ListenableWorker.Result.failure(),
                run(dir) { _, _ -> throw SttError(code, "нет такого пути") },
            )
        }
    }

    /** Расшифровка удалась — причины больше нет, и лента снова показывает обычную заметку. */
    @Test
    fun `удачная расшифровка снимает причину`() {
        val dir = noteDir()
        File(dir, NotesStore.STATUS).writeText(NO_KEY)
        assertEquals(ListenableWorker.Result.success(), run(dir))
        assertFalse(File(dir, NotesStore.STATUS).exists())
    }

    /**
     * Вендор ответил, но слов в ответе нет. Пустой `transcript.md` был бы приговором: воркер при
     * существующем файле выходит `success` и заметку больше никогда не перераспознаёт, а в репо
     * заметок уехал бы пустой транскрипт. Причина при этом не пишется: слов на экране для неё
     * ведущий не назначил, и запись честно остаётся в общей очереди.
     */
    @Test
    fun `пустой ответ вендора не выдаётся за расшифровку`() {
        val dir = noteDir()
        val result = run(dir) { _, _ -> """{"language_code":"rus","text":"","words":[]}""" }
        assertEquals(ListenableWorker.Result.retry(), result)
        assertFalse(File(dir, NotesStore.TRANSCRIPT_MD).exists())
    }

    /**
     * Обрыв записи: `transcript.md` виден целиком или не виден вовсе.
     *
     * Цена прямой записи тихая и дорогая. Убитый посреди `writeText` процесс оставляет обрезанный
     * `transcript.md`, а он навсегда выключает перераспознавание (воркер при существующем файле
     * выходит `success`) и молча уезжает в GitHub куском разговора.
     *
     * Убить процесс в юните нечем, поэтому обрыв ловится с другой стороны — читателем: при прямой
     * записи файл виден пустым и кусками, при `.tmp` + `renameTo` — только целым (переименование
     * атомарно) или отсутствующим. Красным этот тест может стать ТОЛЬКО от подмены записи: на
     * честном коде читатель обрезанного файла не увидит ни при какой раскладке потоков, поэтому
     * гонка здесь не мигает. Образец повтора вместо сна в боевом коде — `RepoCacheTest`.
     */
    @Test
    fun `обрыв записи не оставляет обрезанного transcript md`() {
        val file = File(tmp.newFolder("atomic"), NotesStore.TRANSCRIPT_MD)
        val text = "[00:00] Спикер 1: " + "слово ".repeat(WORDS)
        val torn = java.util.concurrent.atomic.AtomicReference<String>()
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val reader = Thread {
            while (!done.get() && torn.get() == null) {
                runCatching { file.readText() }.getOrNull()?.takeIf { it != text }?.let(torn::set)
            }
        }

        reader.start()
        repeat(WRITES) {
            file.delete()
            NotesStore.writeAtomic(file, text)
        }
        done.set(true)
        reader.join()

        assertEquals("читатель увидел кусок файла длиной ${torn.get()?.length}", null, torn.get())
        assertEquals(text, file.readText())
    }

    private companion object {
        const val OLD_MD = "[00:00] Спикер 1: старая заметка"
        const val NO_KEY = "нет ключа ElevenLabs"

        /** Столько слов даёт около 240 КБ — прямая запись такого файла идёт десятками syscall. */
        const val WORDS = 40_000
        const val WRITES = 200
    }
}
