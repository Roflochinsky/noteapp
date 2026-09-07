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

        /**
         * Коды, после которых повторять бессмысленно: ключ, права или сам запрос сами не починятся,
         * а каждая попытка — 48 МБ трафика на часовой записи.
         */
        private val FATAL = setOf(400, 401, 403)

        /**
         * Всё, что `doWork` решает сам: чей каталог, чей ключ и какой вендор. Вынесено из `doWork`
         * ровно затем, чтобы эти строки исполнял тест: собрать `CoroutineWorker` в юните нечем —
         * `androidx.work:work-testing` в зависимостях нет, а `app/build.gradle.kts` этот срез не
         * трогает. Цена подмены здесь тихая и дорогая: чужой ключ уводит каждую запись в вечный
         * `retry` без единой расшифровки, и весь гейт при этом зелёный.
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
         * дождаться ключа в очереди), и только отказ по коду из [FATAL] кончает попытки.
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
                File(dir, NotesStore.TRANSCRIPT_JSON).writeText(json)
                val md = TranscriptMapper.toMarkdown(TranscriptMapper.fromElevenLabsJson(json))
                File(dir, NotesStore.TRANSCRIPT_MD).writeText(md)
                Log.i(Probe.LOG_TAG, "PROBE:STT_OK note=$noteId chars=${md.length}")
                WorkResult.success()
            } catch (e: SttError) {
                Log.w(Probe.LOG_TAG, "PROBE:STT_HTTP note=$noteId code=${e.code}")
                if (e.code in FATAL) WorkResult.failure() else WorkResult.retry()
            } catch (e: IOException) {
                Log.w(Probe.LOG_TAG, "PROBE:STT_RETRY note=$noteId ${e.message?.take(ERR_PREVIEW)}")
                WorkResult.retry()
            }
    }
}
