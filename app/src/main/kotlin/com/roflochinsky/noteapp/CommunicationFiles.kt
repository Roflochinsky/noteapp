package com.roflochinsky.noteapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.roflochinsky.noteapp.pipeline.CommunicationStore
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

/**
 * SAF inputs are explicitly selected, bounded and decoded strictly; no provider path is trusted.
 */
object CommunicationFiles {
    fun read(context: Context, uri: Uri): String {
        val bytes =
            context.contentResolver.openInputStream(uri)?.use { it.readNBytes(MAX_BYTES + 1) }
                ?: error("Не удалось открыть файл")
        require(bytes.size <= MAX_BYTES) { "Файл слишком большой: максимум 400 КБ" }
        val text =
            try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
                    .removePrefix("\uFEFF")
            } catch (invalid: java.nio.charset.CharacterCodingException) {
                throw IllegalArgumentException(
                    "Не удалось прочитать UTF-8. Сохраните источник как текст UTF-8 и повторите.",
                    invalid,
                )
            }
        require(text.length <= CommunicationStore.MAX_TEXT) {
            "Текст слишком большой: максимум 100 000 символов"
        }
        require(text.isNotBlank()) { "В файле нет текста" }
        return text
    }

    fun write(context: Context, content: String): File {
        val dir = File(context.cacheDir, "context-exports").apply { mkdirs() }
        val file = File(dir, "context-${UUID.randomUUID()}.md")
        file.writeText(content)
        return file
    }

    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.context-files", file)
        val intent =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/markdown"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = android.content.ClipData.newRawUri("Контекст коммуникаций", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        context.startActivity(Intent.createChooser(intent, "Кому передать контекст"))
    }

    private const val MAX_BYTES = 400_000
}
