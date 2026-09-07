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
            transcribeNote(
                dir = NotesStore.noteDir(applicationContext, noteId),
                noteId = noteId,
                key = Settings.elevenLabsKey(applicationContext),
                stt = ElevenLabsClient()::transcribe,
            )
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
         * Всё решение по одной заметке; `doWork` вокруг только достаёт `noteId`, каталог и ключ.
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
