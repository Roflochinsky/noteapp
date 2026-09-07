package com.roflochinsky.noteapp.pipeline

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker.Result as WorkResult
import androidx.work.WorkerParameters
import com.roflochinsky.noteapp.Probe
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Расшифровка одной заметки. Контент ходит через файлы NotesStore, в Data — только noteId (лимит
 * Data 10KB, вердикт LLD-3 плана v1).
 *
 * Вендор один — ElevenLabs (Решение 1 ADR `2026-09-06-stt-elevenlabs-scribe-v2`). Ветки «нет ключа
 * ElevenLabs — попробуем Deepgram» здесь нет намеренно: такая запись получила бы `transcript.md` от
 * старого вендора, а после этого [transcribeNote] её уже никогда не перераспознает.
 */
class TranscribeWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): WorkResult =
        withContext(Dispatchers.IO) {
            val noteId = inputData.getString(KEY_NOTE_ID) ?: return@withContext WorkResult.failure()
            transcribeById(applicationContext, noteId)
        }

    companion object {
        const val KEY_NOTE_ID = "noteId"
        private const val ERR_PREVIEW = 200

        /** Причина в [NotesStore.STATUS], когда ключа ElevenLabs нет вовсе. */
        private const val NO_KEY = "нет ключа ElevenLabs"

        /**
         * 4xx, которые всё-таки про «попробуй позже»: 408 — таймаут запроса, 429 — лимит. Остальные
         * 4xx неисправимы (решение ведущего по находке П4 ревью среза 1).
         */
        private val RETRYABLE_4XX = setOf(408, 429)

        private val CLIENT_ERRORS = 400..499

        /**
         * Повторять бессмысленно: ключ, права или сам запрос сами не починятся, а каждая попытка —
         * 48 МБ трафика на часовой записи. До решения ведущего фатальными были ровно 400/401/403, и
         * 404 (не тот путь) с 422 (не то тело) уходили в бесконечные повторы.
         */
        private fun fatal(code: Int) = code in CLIENT_ERRORS && code !in RETRYABLE_4XX

        /**
         * Всё, что `doWork` решает сам: чей каталог, чей ключ и какой вендор. Вынесено из `doWork`
         * ровно затем, чтобы эти строки исполнял тест: собрать `CoroutineWorker` в юните нечем —
         * `androidx.work:work-testing` в зависимостях нет, а `app/build.gradle.kts` этот срез не
         * трогает. Цена подмены здесь тихая и дорогая: чужой ключ уводит каждую запись в вечный
         * `retry` без единой расшифровки, и весь гейт при этом зелёный.
         *
         * **Тест исполняет каталог и ключ, но НЕ вендора** — он подставляет свой [stt] явным
         * аргументом. Значение по умолчанию остаётся объявленным долгом среза: отличить один боевой
         * клиент от другого офлайн нечем, это ловится живым смоуком или прокликкой владельца.
         * Компилятор здесь не страж: `DeepgramClient::transcribe` имеет ровно тот же тип `(File,
         * String) -> String` и подставляется без единой правки.
         *
         * @param stt тот же шов, что у [transcribeNote]; боевое значение по умолчанию —
         *   единственный вендор пайплайна (Решение 1 ADR).
         */
        fun transcribeById(
            context: Context,
            noteId: String,
            stt: (File, String) -> String = ElevenLabsClient()::transcribe,
        ): WorkResult =
            transcribeNote(
                dir = NotesStore.noteDir(context, noteId),
                noteId = noteId,
                key = Settings.elevenLabsKey(context),
                stt = stt,
            )

        /**
         * Всё решение по одной заметке; [transcribeById] вокруг только выбирает каталог, ключ и
         * вендора, а `doWork` — достаёт `noteId` из `Data`.
         *
         * Порядок веток и есть смысл: расшифрованную заметку не трогаем (бюджет спеки «ровно 1
         * запрос на запись» — это деньги), без ключа просим повторить, а не сдаёмся (запись обязана
         * дождаться ключа в очереди), и попытки кончает только неисправимый отказ вендора — любой
         * 4xx, кроме 408 и 429 (см. [fatal]).
         *
         * @param stt шов распознавания: в бою [ElevenLabsClient.transcribe], в тестах фейк — иначе
         *   проверить судьбу записи при 401 и при обрыве сети можно было бы только живым ключом.
         */
        fun transcribeNote(
            dir: File,
            noteId: String,
            key: String?,
            stt: (File, String) -> String,
        ): WorkResult {
            val audio = File(dir, NotesStore.AUDIO)
            return when {
                !audio.exists() -> WorkResult.failure()
                File(dir, NotesStore.TRANSCRIPT_MD).exists() -> WorkResult.success()
                key == null -> {
                    Log.w(Probe.LOG_TAG, "PROBE:STT_SKIP no_key note=$noteId")
                    reason(dir, NO_KEY)
                    WorkResult.retry()
                }
                else -> recognize(audio, dir, noteId, key, stt)
            }
        }

        private fun recognize(
            audio: File,
            dir: File,
            noteId: String,
            key: String,
            stt: (File, String) -> String,
        ): WorkResult =
            try {
                val json = stt(audio, key)
                // Ответ вендора кладётся целиком: его читают будущие срезы (прокрут по словам).
                NotesStore.writeAtomic(File(dir, NotesStore.TRANSCRIPT_JSON), json)
                val md = TranscriptMapper.toMarkdown(TranscriptMapper.fromElevenLabsJson(json))
                clearReason(dir)
                if (md.isBlank()) {
                    // Слов в ответе нет. Пустой `transcript.md` был бы приговором: заметка
                    // навсегда считалась бы расшифрованной, и пустой транскрипт уехал бы в репо.
                    Log.w(Probe.LOG_TAG, "PROBE:STT_EMPTY note=$noteId")
                    WorkResult.retry()
                } else {
                    NotesStore.writeAtomic(File(dir, NotesStore.TRANSCRIPT_MD), md)
                    Log.i(Probe.LOG_TAG, "PROBE:STT_OK note=$noteId chars=${md.length}")
                    WorkResult.success()
                }
            } catch (e: SttError) {
                Log.w(Probe.LOG_TAG, "PROBE:STT_HTTP note=$noteId code=${e.code}")
                if (fatal(e.code)) {
                    reason(dir, "ошибка ElevenLabs ${e.code}\n${preview(e.body)}")
                    WorkResult.failure()
                } else {
                    clearReason(dir)
                    WorkResult.retry()
                }
            } catch (e: IOException) {
                Log.w(Probe.LOG_TAG, "PROBE:STT_RETRY note=$noteId ${e.message?.take(ERR_PREVIEW)}")
                clearReason(dir)
                WorkResult.retry()
            }

        /**
         * Причина кладётся рядом с записью файлом — по действующему правилу [NotesStore] «статус
         * это наличие файлов, отдельного state-файла нет». Пишется там, где причина известна: нет
         * ключа и отказ вендора по неисправимому коду.
         */
        private fun reason(dir: File, text: String) =
            NotesStore.writeAtomic(File(dir, NotesStore.STATUS), text)

        /**
         * Причина снимается на любом исходе СОСТОЯВШЕЙСЯ попытки распознавания, кроме новой
         * известной причины: удача, пустой ответ, обрыв сети, «попробуй позже». Иначе строка «нет
         * ключа ElevenLabs» пережила бы попытку, сделанную уже с ключом, и врала бы владельцу в
         * ленте до самой удачной расшифровки.
         *
         * Ранние ветки [transcribeNote] («записи нет» и «уже расшифрована») сюда не заходят: до
         * попытки дело не доходит, а причины у такой заметки и не бывает — `transcript.md` пишет
         * только `recognize`, и он снимает причину до того. Протухшую причину снимает ещё одно
         * место, вне воркера: `MainActivity.enqueueWaiting` в момент ввода ключа — там она устарела
         * по построению, а воркер до неё доберётся только на следующей попытке.
         */
        private fun clearReason(dir: File) = File(dir, NotesStore.STATUS).delete()

        /** Начало тела ответа одной строкой: вторая строка причины — для плашки деталки. */
        private fun preview(body: String) = body.take(ERR_PREVIEW).replace(WHITESPACE, " ").trim()

        private val WHITESPACE = Regex("""\s+""")
    }
}
