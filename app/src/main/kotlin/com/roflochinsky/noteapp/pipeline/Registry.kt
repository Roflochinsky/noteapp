package com.roflochinsky.noteapp.pipeline

/**
 * Реестры репо заметок: `projects.md` и `people.md` — markdown-списки в корне, по строке на запись,
 * `- слаг — пояснение` (схема — ADR `docs/adr/2026-08-26-tasks-as-files.md`). Реестр читается
 * глазами в GitHub и правится руками, поэтому разбор терпимый: заголовок, пустые строки и абзацы
 * пояснений пропускаются, маркер списка любой, пояснение после тире отбрасывается.
 *
 * ponytail: только чтение имён — больше от реестра в срезе Н3 никто ничего не просит. Пополнение
 * (`Новый проект` из компа) и переименование персоны — `bd nikitatrubaev-0rk.23` и срез Н8.
 */
object Registry {
    const val PROJECTS = "projects.md"
    const val PEOPLE = "people.md"

    private val ITEM = Regex("""^\s*[-*]\s+(.+)$""")

    /** Имена в порядке файла; дубли и пустые строки отброшены. */
    fun names(md: String?): List<String> =
        md.orEmpty()
            .lineSequence()
            .mapNotNull { ITEM.find(it)?.groupValues?.get(1) }
            .map { it.substringBefore("—").trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
}
