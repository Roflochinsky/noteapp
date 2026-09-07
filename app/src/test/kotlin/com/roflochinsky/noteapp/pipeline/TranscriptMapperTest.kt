package com.roflochinsky.noteapp.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Маппер проверяется на ЖИВОМ ответе Deepgram (docs/research/deepgram-sample-response.json).
 *
 * Форма ElevenLabs — на фикстуре `elevenlabs-sample-response.json`: окно `[0; 56,2 с]` боевого
 * прогона стенда, 264 элемента (132 без `spacing`). Структура настоящая — `start`, `end`, `type`,
 * `speaker_id`, `logprob` перенесены байт в байт; тексты слов постановочные, потому что репо
 * публичный, а боевой ответ — расшифровка личного разговора владельца (решение Р2 плана
 * `docs/plans/2026-09-06-v3-transcript.md`).
 *
 * **Окно держит каждый случай в одиночку — правка фикстуры требует перегона ВСЕГО набора мутаций:**
 * - событие звука `[смеется]` внутри речи одного спикера — `start` 9,81, `speaker_0`;
 * - пауза того же спикера 1320 мс (`end` 7,00 → `start` 8,32) — ниже порога, реплика не рвётся;
 * - пауза того же спикера 5020 мс (`end` 47,08 → `start` 52,10) — выше порога, реплика рвётся;
 * - шесть смен спикера — 12,18; 22,78; 31,42; 46,38; 55,04; 56,12.
 *
 * Случая «пауза ровно 1500 мс» и случая «первым говорит `speaker_1`» в окне нет — они проверяются
 * маленькими JSON-литералами ниже. Литерал здесь разрешён планом именно потому, что боевого
 * свидетеля на эти два случая в окне не нашлось (правило репо «сочинять json нельзя» —
 * [GithubClientTest]).
 */
class TranscriptMapperTest {

    private fun resource(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource(name)) { "нет образца $name" }.readText()

    private val sample = resource("deepgram-sample-response.json")

    private val elevenlabs = resource("elevenlabs-sample-response.json")

    @Test
    fun `живой ответ парсится в реплики со спикерами и тайм-кодами`() {
        val t = TranscriptMapper.fromDeepgramJson(sample)
        assertTrue("должны быть реплики", t.utterances.isNotEmpty())
        val first = t.utterances.first()
        assertEquals(0, first.speaker)
        assertTrue("startMs должен быть > 0", first.startMs > 0)
        assertTrue("текст реплики не пуст", first.text.isNotBlank())
    }

    @Test
    fun `markdown по формату note-format - Спикер N и мм-сс`() {
        val t = TranscriptMapper.fromDeepgramJson(sample)
        val md = TranscriptMapper.toMarkdown(t)
        // Спикер нумеруется с 1 (Deepgram даёт 0), тайм-код [мм:сс]
        assertTrue(
            md,
            md.lineSequence().first().matches(Regex("""\[\d{2}:\d{2}] Спикер \d+: .+""")),
        )
        assertTrue("спикер 0 стал Спикером 1", md.contains("Спикер 1:"))
    }

    @Test
    fun `тайм-код форматируется мм-сс с ведущими нулями`() {
        assertEquals("[00:05]", TranscriptMapper.timecode(5_400))
        assertEquals("[01:31]", TranscriptMapper.timecode(91_000))
        assertEquals("[12:31]", TranscriptMapper.timecode(751_000))
    }

    /**
     * Критерий приёмки 2: реплики заметки совпадают со сборкой стенда. Ожидание получено прогоном
     * правила `bin/stt-compare-html` (`GAP = 1.5`, пауза считается от `end` предыдущего элемента до
     * `start` следующего) по этой самой фикстуре — не пересчётом кода маппера.
     *
     * Единственное расхождение с эталоном разрешено в пользу спеки (решение Р7 плана): стенд
     * оборачивает событие звука в круглые скобки (`bin/stt-compare-html:65` → `([смеется])`), а
     * замороженный контракт требует его как пришло — `[смеется]`.
     */
    @Test
    fun `реплики фикстуры совпадают с правилом стенда bin-stt-compare-html`() {
        val md = TranscriptMapper.toMarkdown(TranscriptMapper.fromElevenLabsJson(elevenlabs))
        assertEquals(
            """
            [00:01] Спикер 1: Собрали новый релиз, но пайплайн упал при сборке. Нужно починить утечку очереди, затем слить ветку. Срок пятница, значит сегодня смотрим только гейт. Стенд гоняли вчера, отчёт [смеется] лежит
            [00:12] Спикер 2: рядом, план тоже. Цифры сходятся, второй прогон подтвердил вывод. Кэш сборки живёт дольше демона, значит чистим его руками. Давайте запишем решение, чтобы завтра спор закончился. Остался вопрос про
            [00:22] Спикер 1: таймауты: ждать ответ дольше срока никому неохота. Предлагаю поднять предел, потом вернёмся после замера. Логи чистим каждый вечер, сборка проходит
            [00:31] Спикер 2: быстрее прежнего почти вдвое. Осталось проверить старые заметки: экран открывается без сбоев. Затем соберём апк, отдадим владельцу для прокликки. Если внезапно отвалится, откатимся до прошлого коммита, спокойно разберёмся. Ветку трогать рано, сперва нужен зелёный прогон. Мутации гоняем одним заходом, шесть штук хватит. Отчёт допишем вечером, ссылку положим
            [00:46] Спикер 1: рядом.
            [00:52] Спикер 1: Дальше пойдёт ревью. Журнал
            [00:55] Спикер 2: среза готов, дальше
            [00:56] Спикер 1: ведущий.
            """
                .trimIndent(),
            md,
        )
    }

    /**
     * Пауза 1320 мс (`end` 7,00 → `start` 8,32) ниже порога 1,5 с — слова остаются одной репликой.
     */
    @Test
    fun `пауза ниже порога не рвёт реплику`() {
        val t = TranscriptMapper.fromElevenLabsJson(elevenlabs)
        assertNull("реплика порвалась на паузе 1320 мс", t.utterances.find { it.startMs == 8_320L })
        assertTrue(t.utterances.first().text, t.utterances.first().text.contains("смотрим только"))
    }

    /**
     * Пауза 5020 мс (`end` 47,08 → `start` 52,10) выше порога — реплика рвётся, хотя спикер тот же.
     * Спикер сверяется именно поэтому: иначе разрез объяснялся бы сменой говорящего.
     */
    @Test
    fun `пауза выше порога рвёт реплику того же спикера`() {
        val t = TranscriptMapper.fromElevenLabsJson(elevenlabs)
        val i = t.utterances.indexOfFirst { it.startMs == 46_380L }
        assertTrue("нет реплики перед паузой 5020 мс", i >= 0)
        assertEquals("рядом.", t.utterances[i].text)
        assertEquals(52_100L, t.utterances[i + 1].startMs)
        assertEquals(t.utterances[i].speaker, t.utterances[i + 1].speaker)
    }

    /** Смена спикера на 12,18 с при паузе 1020 мс: порог не при чём, реплику рвёт другой голос. */
    @Test
    fun `смена спикера рвёт реплику`() {
        val t = TranscriptMapper.fromElevenLabsJson(elevenlabs)
        assertEquals(0, t.utterances[0].speaker)
        assertEquals(12_180L, t.utterances[1].startMs)
        assertEquals(1, t.utterances[1].speaker)
    }

    /**
     * Критерий приёмки 3. Скобки уже стоят в ответе вендора — обернуть их ещё раз значит выдать
     * `[[смеется]]` или `([смеется])`; проверяется точной подстрокой вместе с соседями, а не
     * `contains("смеется")`, который выживет при любой обёртке.
     */
    @Test
    fun `событие звука попадает в текст как пришло`() {
        val first = TranscriptMapper.fromElevenLabsJson(elevenlabs).utterances.first().text
        assertTrue(first, first.contains("отчёт [смеется] лежит"))
        assertFalse(first, first.contains("[[смеется]]"))
        assertFalse(first, first.contains("([смеется])"))
    }

    /**
     * Граница порога: пауза ровно 1500 мс реплику рвёт (`пауза < 1,5 с` — строгое неравенство). В
     * окне фикстуры такого случая нет, свидетель в боевом ответе — 1317,092 → 1318,592.
     */
    @Test
    fun `пауза ровно 1500 мс рвёт реплику`() {
        val json =
            """
            {"words":[
              {"text":"первое","start":9.5,"end":10.0,"type":"word","speaker_id":"speaker_0"},
              {"text":" ","start":10.0,"end":11.5,"type":"spacing","speaker_id":"speaker_0"},
              {"text":"второе","start":11.5,"end":12.0,"type":"word","speaker_id":"speaker_0"}
            ]}
            """
                .trimIndent()
        val t = TranscriptMapper.fromElevenLabsJson(json)
        assertEquals(listOf(9_500L, 11_500L), t.utterances.map { it.startMs })
    }

    /**
     * Решение Р6: номер спикера — цифра из `speaker_id`, а не порядок появления. По цифре первым
     * выйдет «Спикер 2», по порядку появления — «Спикер 1»; на фикстуре первым идёт `speaker_0`, и
     * оба правила дают одно и то же, поэтому свидетель — литерал. `speaker_id` без цифры → 0: форма
     * поля вендорская, а нумерация в заметке ломаться не должна. Третий элемент отнесён на 2,5 с
     * намеренно: с цифрой 0 он совпал бы со вторым по спикеру и склеился бы с ним в реплику.
     */
    @Test
    fun `номер спикера — цифра из speaker_id, а не порядок появления`() {
        val json =
            """
            {"words":[
              {"text":"раз","start":0.0,"end":0.2,"type":"word","speaker_id":"speaker_1"},
              {"text":"два","start":0.25,"end":0.5,"type":"word","speaker_id":"speaker_0"},
              {"text":"три","start":2.5,"end":2.7,"type":"word","speaker_id":"неизвестно"}
            ]}
            """
                .trimIndent()
        val t = TranscriptMapper.fromElevenLabsJson(json)
        assertEquals(listOf(1, 0, 0), t.utterances.map { it.speaker })
        assertTrue(
            TranscriptMapper.toMarkdown(t),
            TranscriptMapper.toMarkdown(t).startsWith("[00:00] Спикер 2: раз"),
        )
    }
}
