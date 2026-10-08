package com.roflochinsky.noteapp.pipeline

import java.io.File
import java.time.LocalDate
import java.util.UUID
import org.json.JSONObject

/** Local-only sources. Never consumed by the recording or repository workers. */
data class Communication(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val source: String = "Другое",
    val project: String = "",
    val date: String = "",
    val url: String = "",
    val text: String = "",
    val decisions: String = "",
    val commitments: String = "",
    val owners: String = "",
    val deadlines: String = "",
    val blockers: String = "",
)

fun Communication.draftSize(): Int =
    listOf(
            title,
            source,
            project,
            date,
            url,
            text,
            decisions,
            commitments,
            owners,
            deadlines,
            blockers,
        )
        .sumOf { it.length }

class CommunicationStore(private val directory: File) {
    @Synchronized
    fun list(): List<Communication> =
        directory
            .listFiles { file -> file.extension == "json" }
            .orEmpty()
            .map { file ->
                val json = JSONObject(file.readText())
                Communication(
                    id = json.getString("id"),
                    title = json.getString("title"),
                    source = json.optString("source", "Другое"),
                    project = json.optString("project"),
                    date = json.optString("date"),
                    url = json.optString("url"),
                    text = json.getString("text"),
                    decisions = json.optString("decisions"),
                    commitments = json.optString("commitments"),
                    owners = json.optString("owners"),
                    deadlines = json.optString("deadlines"),
                    blockers = json.optString("blockers"),
                )
            }
            .sortedWith(compareByDescending<Communication> { it.date }.thenBy { it.title })

    @Synchronized
    fun save(input: Communication): Communication {
        val value =
            input.copy(
                title = input.title.trim(),
                project = input.project.trim(),
                date = input.date.trim(),
                url = input.url.trim(),
            )
        validate(value)
        check(directory.isDirectory || directory.mkdirs()) { "Не удалось создать хранилище" }
        val json =
            JSONObject().apply {
                put("version", 1)
                put("id", value.id)
                put("title", value.title)
                put("source", value.source)
                put("project", value.project)
                put("date", value.date)
                put("url", value.url)
                put("text", value.text)
                put("decisions", value.decisions)
                put("commitments", value.commitments)
                put("owners", value.owners)
                put("deadlines", value.deadlines)
                put("blockers", value.blockers)
            }
        NotesStore.writeAtomic(File(directory, "${value.id}.json"), json.toString())
        return value
    }

    private fun validate(value: Communication) {
        require(value.draftSize() <= MAX_DRAFT) {
            "Всего максимум 120 000 символов. Разделите источник."
        }
        require(value.id.matches(Regex("[a-zA-Z0-9-]{1,80}"))) { "Некорректный идентификатор" }
        require(value.title.isNotBlank()) { "Добавьте заголовок" }
        require(value.text.isNotBlank()) { "Добавьте исходный текст" }
        require(value.text.length <= MAX_TEXT) {
            "Текст слишком большой: максимум 100 000 символов"
        }
        require(value.title.length <= MAX_TITLE && value.project.length <= MAX_TITLE) {
            "Слишком длинное поле"
        }
        require(value.date.isBlank() || runCatching { LocalDate.parse(value.date) }.isSuccess) {
            "Дата: ГГГГ-ММ-ДД, либо оставьте пустой"
        }
        require(
            value.url.isBlank() ||
                value.url.startsWith("https://") ||
                value.url.startsWith("http://")
        ) {
            "Ссылка должна начинаться с https:// или http://"
        }
        require(
            listOf(
                    value.url,
                    value.decisions,
                    value.commitments,
                    value.owners,
                    value.deadlines,
                    value.blockers,
                )
                .all { it.length <= MAX_TEXT }
        ) {
            "Слишком длинное поле"
        }
    }

    companion object {
        fun onDevice(context: android.content.Context): CommunicationStore =
            CommunicationStore(File(context.noBackupFilesDir, "communications"))

        const val MAX_TEXT = 100_000
        const val MAX_DRAFT = 120_000
        const val MAX_TITLE = 500
    }
}
