package com.roflochinsky.noteapp.pipeline

/**
 * Реестры репо заметок: `projects.md` и `people.md` — markdown-списки в корне, по строке на запись,
 * `- слаг — пояснение` (схема — ADR `docs/adr/2026-08-26-tasks-as-files.md`). Реестр читается
 * глазами в GitHub и правится руками, поэтому разбор терпимый: заголовок, пустые строки и абзацы
 * пояснений пропускаются, маркер списка любой, пояснение после тире отбрасывается.
 *
 * Читает и пополняет: `Новый проект` из шторки дописывает строку в `projects.md` тем же коммитным
 * путём, что и правки задач (`bd nikitatrubaev-0rk.23`). Переименование персоны — срез Н8.
 *
 * ponytail: имена сравниваются без регистра и только по себе — ни слагов, ни транслита. Реестр
 * личный и короткий: «tgsum» и «TGSum» владелец имел в виду один проект, а «tgsum» и «tg-sum» — два
 * разных, и разбирать это за него приложение не берётся.
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

    /** Имя реестра из ввода владельца: одна строка без пояснения после тире; пустое — `null`. */
    fun name(input: String): String? =
        input.replace(WS, " ").substringBefore("—").trim().takeIf { it.isNotEmpty() }

    /** Имя, которое в реестре уже стоит (регистр не важен), или `null` — такого ещё нет. */
    fun match(names: List<String>, name: String): String? =
        names.firstOrNull { it.equals(name, ignoreCase = true) }

    /**
     * Реестр с новой строкой `- имя` после последнего элемента списка. `null` — писать нечего: имя
     * пустое или уже стоит в файле. Дубликат завести нельзя: два одинаковых имени — это два
     * значения чипа с одним смыслом.
     */
    fun add(md: String?, input: String): String? {
        val name = name(input) ?: return null
        if (match(names(md), name) != null) return null
        val lines = md.orEmpty().trimEnd('\n').split("\n").toMutableList()
        val at = lines.indexOfLast { ITEM.matches(it) }
        lines.add(if (at >= 0) at + 1 else lines.size, "- $name")
        return lines.joinToString("\n") + "\n"
    }

    private val WS = Regex("""\s+""")
}
