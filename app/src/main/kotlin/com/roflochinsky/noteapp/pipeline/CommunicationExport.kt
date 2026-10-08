package com.roflochinsky.noteapp.pipeline

import java.time.LocalDateTime

/** A derived handoff document, never the canonical GitHub note schema. */
object CommunicationExport {
    private const val MIN_FENCE = 3

    @Suppress(
        "LongParameterList"
    ) // Explicit immutable snapshot inputs; no hidden repository reads.
    fun build(
        project: String?,
        sources: List<Communication>,
        notes: List<FeedItem>,
        tasks: List<TaskFile.Task>,
        transcript: (String) -> String,
        exportedAt: String = LocalDateTime.now().toString(),
        pendingPaths: Set<String> = emptySet(),
        sync: SyncStatus? = null,
    ): String = buildString {
        append("# Контекст коммуникаций\n\n")
        append("Проект: ${project ?: "Все проекты"}\nВыгружено: $exportedAt\n\n")
        append(
            "Снимок данных с этого устройства. Перед передачей проверьте содержание и получателя. "
        )
        append(
            "Пустые поля не означают отсутствие договорённостей. Автоматической передачи помощнику нет.\n\n"
        )
        append(
            "Состояние синхронизации: ${syncLabel(sync)}. Свежесть ограничена последним обновлением на устройстве.\n\n"
        )
        sources.filter { project == null || it.project == project }.forEach { source(it) }
        notes
            .filter { project == null || it.project == project }
            .forEach { note(it, transcript, pendingPaths) }
        val selectedTasks = tasks.filter { project == null || it.project == project }
        if (selectedTasks.isNotEmpty()) append("## Задачи из локального снимка репозитория\n\n")
        selectedTasks.forEach { task(it, pendingPaths) }
    }

    private fun syncLabel(sync: SyncStatus?): String =
        when (sync) {
            SyncStatus.OK -> "последняя проверка без ошибок"
            SyncStatus.NO_TOKEN -> "GitHub не подключён"
            SyncStatus.OFFLINE -> "нет связи с GitHub"
            SyncStatus.NO_ACCESS -> "нет доступа к репозиторию"
            SyncStatus.RATE_LIMIT -> "лимит запросов GitHub"
            null -> "не проверено или обновление ещё идёт"
        }

    private fun StringBuilder.source(source: Communication) {
        append("## ${source.title}\n\nИсточник: ${source.source}\n")
        append("Хранение: только на этом устройстве\nID: ${source.id}\n")
        field("Проект", source.project)
        field("Дата коммуникации", source.date)
        field("Ссылка", source.url)
        section("Решения (добавлены пользователем)", source.decisions)
        section("Обещания (добавлены пользователем)", source.commitments)
        section("Ответственные (добавлены пользователем)", source.owners)
        section("Сроки (добавлены пользователем)", source.deadlines)
        section("Блокеры (добавлены пользователем)", source.blockers)
        append("\n### Исходный текст\n\n")
        fenced(source.text)
    }

    private fun StringBuilder.note(
        item: FeedItem,
        transcript: (String) -> String,
        pendingPaths: Set<String>,
    ) {
        append("## ${item.title.ifBlank { "Запись ${item.noteId ?: item.ref}" }}\n\n")
        field("Проект", item.project.orEmpty())
        field("Дата", item.time?.toString().orEmpty())
        field("Путь в репозитории", item.path.orEmpty())
        val delivery =
            if (item.pushed) "есть подтверждение GitHub" else "локальная запись / очередь"
        append("Доставка: $delivery\n")
        pending(item.path, pendingPaths)
        val body = item.note?.body ?: item.noteId?.let(transcript).orEmpty()
        if (body.isNotBlank()) {
            append("\n### Содержимое заметки\n\n")
            fenced(body)
        } else append("\nТранскрипта пока нет. Аудио в эту выгрузку не включено.\n\n")
    }

    private fun StringBuilder.task(task: TaskFile.Task, pendingPaths: Set<String>) {
        append("### ${task.title}\n\nСтатус: ${task.status}\n")
        pending(task.path, pendingPaths)
        field("Проект", task.project.orEmpty())
        field("Срок", task.due?.toString().orEmpty())
        field("Источник", task.source.orEmpty())
        field("Путь", task.path)
        if (task.body.isNotBlank()) fenced(task.body)
        append("\n")
    }

    private fun StringBuilder.pending(path: String?, paths: Set<String>) {
        if (path in paths)
            append("Есть локальные правки в очереди: они ещё не подтверждены GitHub.\n")
    }

    private fun StringBuilder.field(name: String, value: String) {
        if (value.isNotBlank()) append("$name: $value\n")
    }

    private fun StringBuilder.section(name: String, value: String) {
        if (value.isNotBlank()) {
            append("\n### $name\n\n")
            fenced(value)
        }
    }

    // Keep arbitrary imported Markdown literally readable, including nested backtick fences.
    private fun StringBuilder.fenced(value: String) {
        val length = Regex("`+").findAll(value).maxOfOrNull { it.value.length } ?: 0
        val fence = "`".repeat(maxOf(MIN_FENCE, length + 1))
        append("$fence\n$value")
        if (!value.endsWith('\n')) append('\n')
        append("$fence\n\n")
    }
}
