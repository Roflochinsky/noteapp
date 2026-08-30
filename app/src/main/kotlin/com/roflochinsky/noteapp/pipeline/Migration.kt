package com.roflochinsky.noteapp.pipeline

import java.time.LocalDate

/**
 * Разовая миграция репо заметок v1 → v2 (спека, критерий 17): чекбоксы секции «Задачи» становятся
 * файлами `tasks/`, а на их месте в заметке остаются ссылки. Чистая: на входе слепок репо `путь →
 * текст`, на выходе — план правок для [BatchPlan]. Ни сети, ни часов.
 *
 * Три свойства, ради которых это единственный срез, переписывающий боевые данные владельца:
 * - **весь план виден до запуска** — [Plan.made] перечисляет каждую будущую задачу поимённо;
 * - **один коммит** ([MESSAGE]) — применяется целиком или не применяется вовсе, откат одним `git
 *   revert`;
 * - **повторный прогон ничего не делает**: состояние заметки и есть признак миграции — чекбокс,
 *   ставший ссылкой, второй раз не подходит ни под одно правило. Отдельного журнала «что уже
 *   мигрировано» нет и не нужно.
 *
 * Имя файла задачи детерминировано: дата берётся из самой заметки (`recorded`, иначе дата в имени
 * файла), слаг — из текста чекбокса ([TaskFile.fileName]). От дня запуска не зависит ничего,
 * поэтому сухой прогон и настоящий дают один и тот же результат.
 */
object Migration {

    /** Сообщение коммита — по нему миграцию находят в истории и снимают одним `revert`. */
    const val MESSAGE = "migration: tasks v2"

    /**
     * Что превратится в задачу: [note] — заметка-источник, [path] — будущий файл, [subtasks] — её
     * подпункты, вложенные в заметке под этот чекбокс (своих файлов у них нет).
     */
    data class Made(
        val note: String,
        val title: String,
        val path: String,
        val done: Boolean,
        val subtasks: List<TaskFile.Subtask> = emptyList(),
    )

    /**
     * [skipped] — заметки с чекбоксами, у которых не нашлось даты: имя файла задачи получилось бы
     * недетерминированным (сегодняшним), а такое молча писать в чужой репо нельзя.
     */
    data class Plan(
        val changes: List<BatchPlan.Change>,
        val made: List<Made>,
        val skipped: List<String>,
    ) {
        val isEmpty: Boolean
            get() = changes.isEmpty()
    }

    fun plan(files: Map<String, String>): Plan {
        val taken = files.keys.toMutableSet()
        val changes = mutableListOf<BatchPlan.Change>()
        val made = mutableListOf<Made>()
        val skipped = mutableListOf<String>()
        // Порядок обхода задаёт суффиксы при совпадении имён — сортируем, чтобы он не зависел от
        // порядка ключей в карте (и от того, как их вернул GitHub).
        files.keys.filter(::isNote).sorted().forEach { path ->
            val text = files.getValue(path)
            val note = NoteFile.parse(path, text) ?: return@forEach
            if (!hasCheckbox(text)) return@forEach
            val date = date(note, path)
            if (date == null) {
                skipped += path
                return@forEach
            }
            val fresh = migrate(path, text, note, date, taken)
            changes += fresh.changes
            made += fresh.made
        }
        return Plan(changes, made, skipped)
    }

    /** Будущая задача, пока строки заметки ещё идут: подпункты долепляются к ней по ходу. */
    private class Draft(
        val title: String,
        val path: String,
        val done: Boolean,
        val indent: Int,
        val subtasks: MutableList<TaskFile.Subtask> = mutableListOf(),
    )

    /** Одна заметка: чекбоксы её секции «Задачи» → файлы задач, строки → ссылки. */
    private fun migrate(
        path: String,
        text: String,
        note: NoteFile.Note,
        date: LocalDate,
        taken: MutableSet<String>,
    ): Plan {
        val drafts = mutableListOf<Draft>()
        var inTasks = false
        var parent: Draft? = null
        val lines =
            text.split("\n").map { line ->
                when {
                    HEADER.matches(line) -> {
                        inTasks = true
                        parent = null
                        return@map line
                    }
                    inTasks && closes(line) -> {
                        inTasks = false
                        parent = null
                    }
                }
                val box = (if (inTasks) CHECKBOX.find(line) else null) ?: return@map line
                val indent = box.groupValues[INDENT]
                val title = box.groupValues[TITLE].trim()
                val done = box.groupValues[MARK] != " "
                // Вложеннее предыдущей задачи — это её подпункт; иначе новая задача, и её отступ
                // становится уровнем «верха» (список секции мог быть сдвинут целиком).
                val owner = parent?.takeIf { indent.length > it.indent }
                val task =
                    if (owner == null) {
                        val name = TaskFile.fileName(date, title, taken)
                        Draft(title, TaskFile.DIR + name, done, indent.length)
                            .also { drafts += it }
                            .also { parent = it }
                            .also { taken += it.path }
                    } else {
                        owner.subtasks += TaskFile.Subtask(title, done)
                        owner
                    }
                // Заметка могла прийти с CRLF: `\s*$` шаблона съедает `\r`, и без возврата
                // на место в файле завелись бы смешанные переводы строк.
                val eol = if (line.endsWith("\r")) "\r" else ""
                "$indent- [$title](../${task.path})$eol"
            }
        if (drafts.isEmpty()) return Plan(emptyList(), emptyList(), emptyList())
        // Заметка переписывается построчно: frontmatter и транскрипт не пересобираются вовсе
        // (решение LLD-9), меняются ровно строки чекбоксов.
        return Plan(
            drafts.map { BatchPlan.Put(it.path, task(it, note, date)) } +
                BatchPlan.Put(path, lines.joinToString("\n")),
            drafts.map { Made(path, it.title, it.path, it.done, it.subtasks.toList()) },
            emptyList(),
        )
    }

    /**
     * Задача-минимум по ADR: обязательные поля есть, необязательных нет. `project` наследуется от
     * заметки — проект принадлежит работе, и без него чипы для мигрированных задач пусты. Теги —
     * нет: тег описывает заметку, а из текста чекбокса вывести его нечем, и лишняя метка в боевом
     * репо хуже отсутствующей. `done` не ставится даже у закрытых — даты закрытия в v1 не
     * существовало, а выдумывать её ADR прямо запрещает («задача со `status: done` без поля `done`
     * показывается в конце секции без даты»).
     */
    private fun task(draft: Draft, note: NoteFile.Note, date: LocalDate): String =
        TaskFile.build(
            TaskFile.Task(
                path = draft.path,
                title = draft.title,
                status = if (draft.done) TaskFile.STATUS_DONE else TaskFile.STATUS_OPEN,
                project = note.project,
                source = note.path,
                created = date,
                body = body(draft.subtasks),
            )
        )

    /**
     * Подпункты заметки — секция «Подзадачи» тела задачи: формат под ADR не меняется, меняется
     * только то, что миграция в него пишет. Заголовок берётся у [Edit] — того же, кто дописывает
     * подзадачи из приложения, чтобы секция в файле была одна.
     */
    private fun body(subtasks: List<TaskFile.Subtask>): String =
        if (subtasks.isEmpty()) {
            ""
        } else {
            subtasks.joinToString(
                separator = "\n",
                prefix = Edit.SUBTASKS_HEADING + "\n",
                transform = { "- [${if (it.done) "x" else " "}] ${it.text}" },
            )
        }

    /**
     * `inbox/` — вотчина Action (решение LLD-7), `tasks/` — уже v2, реестры не заметки. Публичный:
     * прогон миграции по этому же правилу решает, чей текст вообще качать из репо.
     */
    fun isNote(path: String): Boolean =
        path.endsWith(".md") && !path.startsWith("inbox/") && !path.startsWith(TaskFile.DIR)

    private fun hasCheckbox(text: String): Boolean =
        text.lineSequence().any { CHECKBOX.matches(it) }

    /** Секция кончается пустой строкой, заголовком `##` или следующим жирным блоком саммари. */
    private fun closes(line: String): Boolean =
        line.isBlank() || line.trimStart().startsWith("#") || line.trimStart().startsWith("**")

    private fun date(note: NoteFile.Note, path: String): LocalDate? =
        parse(note.fields["recorded"]?.take(DATE_LEN)) ?: parse(DATE.find(path)?.value)

    private fun parse(value: String?): LocalDate? =
        value?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    private const val DATE_LEN = 10
    private const val INDENT = 1
    private const val MARK = 2
    private const val TITLE = 3
    /**
     * `(?!\()` — та самая правка, которой держится критерий 17 «ни одна заметка не теряет текст».
     * Заголовок ровно `x` даёт ссылку `- [x](../tasks/2026-08-12-x.md)`, и без этого запрета она
     * снова подходит под шаблон: второй прогон читает её как сделанный чекбокс с заголовком
     * «(../tasks/…md)» и затирает ссылку. Скобка сразу после `]` — это markdown-ссылка, а не
     * чекбокс; у настоящего чекбокса между ними всегда пробел.
     */
    private val CHECKBOX = Regex("""^(\s*)[-*]\s*\[([ xX])](?!\()\s*(.+?)\s*$""")
    private val HEADER = Regex("""^\s*(\*\*Задачи[.:]?\*\*|#+\s*Задачи)\s*$""")
    private val DATE = Regex("""\d{4}-\d{2}-\d{2}""")
}
