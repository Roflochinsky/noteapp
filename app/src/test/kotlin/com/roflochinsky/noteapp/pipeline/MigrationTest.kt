package com.roflochinsky.noteapp.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разовая миграция v1 → v2: чекбоксы секции «Задачи» становятся файлами `tasks/` и ссылками.
 *
 * Якорь — настоящие файлы тестового репо `voice-notes-test`, снятые побайтово в
 * `app/src/test/resources/repo/` (git-блобы `098da1a…` немигрированной заметки и `ee2961f…` уже
 * мигрированной). Это единственный срез, который массово переписывает боевые данные владельца,
 * поэтому идемпотентность здесь проверяется прогоном плана поверх собственного результата, а не
 * рассуждением.
 */
class MigrationTest {

    private val v1 = "идеи/2026-08-12-eksport-v-notion-renamed.md"
    private val v2 = "встречи/2026-08-24-1807-reliz-tgsum.md"
    private val migrated = "tasks/2026-08-12-posmotret-limity-notion-api.md"

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/repo/$name")) { "нет фикстуры $name" }.readText()

    private fun repo(): Map<String, String> =
        mapOf(v1 to fixture("note-v1-checkbox.md"), v2 to fixture("note-v2-links.md"))

    /** Применяет план к слепку репо — так же, как это сделает один коммит. */
    private fun apply(files: Map<String, String>, plan: Migration.Plan): Map<String, String> {
        val next = files.toMutableMap()
        plan.changes.forEach { change ->
            when (change) {
                is BatchPlan.Put -> next[change.path] = change.content
                is BatchPlan.Delete -> next.remove(change.path)
            }
        }
        return next
    }

    @Test
    fun `чекбокс настоящей заметки становится файлом задачи и ссылкой на него`() {
        val plan = Migration.plan(repo())
        val made = plan.made.single()
        assertEquals(v1, made.note)
        assertEquals("посмотреть лимиты Notion API", made.title)
        assertEquals(migrated, made.path)

        val task = TaskFile.parse(migrated, put(plan, migrated))
        assertEquals("посмотреть лимиты Notion API", task.title)
        assertEquals(TaskFile.STATUS_OPEN, task.status)
        assertEquals("P2", task.priority)
        assertEquals(v1, task.source)
        assertEquals("2026-08-12", task.created.toString())

        val note = put(plan, v1)
        assertTrue(
            note,
            note.contains(
                "- [посмотреть лимиты Notion API](../tasks/2026-08-12-posmotret-limity-notion-api.md)"
            ),
        )
        assertFalse(note, note.contains("- [ ]"))
    }

    @Test
    fun `в заметке меняются только строки чекбоксов — остальной текст байт в байт прежний`() {
        val before = fixture("note-v1-checkbox.md")
        val after = put(Migration.plan(repo()), v1)
        val changed = before.lines().zip(after.lines()).filter { (a, b) -> a != b }
        assertEquals(before.lines().size, after.lines().size)
        assertEquals(1, changed.size)
        assertEquals("- [ ] посмотреть лимиты Notion API", changed.single().first)
    }

    @Test
    fun `уже мигрированная заметка со ссылками не мигрирует повторно`() {
        val plan = Migration.plan(mapOf(v2 to fixture("note-v2-links.md")))
        assertTrue(plan.changes.toString(), plan.isEmpty)
    }

    @Test
    fun `второй прогон поверх собственного результата не меняет ничего`() {
        val once = Migration.plan(repo())
        assertFalse(once.isEmpty)
        val after = apply(repo(), once)
        val twice = Migration.plan(after)
        assertTrue(twice.changes.toString(), twice.isEmpty)
        assertEquals(emptyList<Migration.Made>(), twice.made)
    }

    @Test
    fun `имя задачи занято — берётся суффикс, чужой файл не трогаем`() {
        val busy = repo() + (migrated to "чужой файл")
        val plan = Migration.plan(busy)
        assertEquals("tasks/2026-08-12-posmotret-limity-notion-api-2.md", plan.made.single().path)
        assertTrue(plan.changes.none { it.path == migrated })
    }

    @Test
    fun `сделанный чекбокс переносится статусом done без даты закрытия`() {
        val note = note(body = "**Задачи.**\n- [x] уже сделано\n")
        val plan = Migration.plan(mapOf("идеи/2026-08-12-a.md" to note))
        val task = TaskFile.parse("", put(plan, plan.made.single().path))
        assertEquals(TaskFile.STATUS_DONE, task.status)
        assertEquals(null, task.done)
    }

    @Test
    fun `проект заметки достаётся задаче, теги заметки — нет`() {
        val note =
            note(head = "project: tgsum\ntags: [релиз]\n", body = "**Задачи.**\n- [ ] дело\n")
        val plan = Migration.plan(mapOf("встречи/2026-08-12-a.md" to note))
        val task = TaskFile.parse("", put(plan, plan.made.single().path))
        assertEquals("tgsum", task.project)
        assertEquals(emptyList<String>(), task.tags)
    }

    @Test
    fun `inbox и сами задачи не мигрируются — там работает Action`() {
        val checkbox = note(body = "**Задачи.**\n- [ ] дело\n")
        val files =
            mapOf(
                "inbox/2026-08-12-1922.md" to checkbox,
                "tasks/2026-08-12-a.md" to checkbox,
                "projects.md" to "# Проекты\n\n- tgsum — раз\n",
            )
        assertTrue(Migration.plan(files).changes.toString(), Migration.plan(files).isEmpty)
    }

    @Test
    fun `чекбокс вне секции Задачи не трогаем — это чужой текст заметки`() {
        val note = note(body = "**Ключевое.**\n- [ ] это не задача, а строчка саммари\n")
        assertTrue(Migration.plan(mapOf("идеи/2026-08-12-a.md" to note)).isEmpty)
    }

    /**
     * Опасное направление — чекбокс ПОСЛЕ секции: если секция перестанет закрываться, миграция
     * поедет по «Ключевому», следующим жирным блокам и транскрипту, переписывая чужой текст
     * заметки. Закрывают секцию все три признака сразу, поэтому проверяются все три.
     */
    @Test
    fun `секция Задачи закрылась — дальше снова чужой текст`() {
        listOf("\n", "## Ключевое\n", "**Ключевое.**\n").forEach { closer ->
            val body = "**Задачи.**\n- [ ] дело\n" + closer + "- [ ] это не задача\n"
            val plan = Migration.plan(mapOf("идеи/2026-08-12-a.md" to note(body = body)))
            assertEquals(closer, listOf("дело"), plan.made.map { it.title })
        }
    }

    /**
     * Критерий 17 спеки: ни одна заметка не теряет текст. Заголовок ровно `x` даёт ссылку `-
     * [x](../tasks/2026-08-12-x.md)`, а она снова подходит под шаблон чекбокса: второй прогон
     * прочёл бы её как сделанный чекбокс с заголовком «(../tasks/…md)», завёл мусорную задачу и
     * затёр ссылку — заголовок «x» из заметки исчез бы.
     */
    @Test
    fun `односимвольный заголовок не съедается вторым прогоном`() {
        listOf(" ", "x").forEach { mark ->
            listOf("x", "X", "(в скобках)").forEach { title ->
                val path = "идеи/2026-08-12-a.md"
                val files =
                    mapOf(path to note(body = "**Задачи.**\n- [" + mark + "] " + title + "\n"))
                val once = Migration.plan(files)
                assertEquals(listOf(title), once.made.map { it.title })
                val after = apply(files, once)
                val twice = Migration.plan(after)
                assertTrue("[" + mark + "] " + title + ": " + twice.made, twice.isEmpty)
                assertTrue(
                    after.getValue(path),
                    after.getValue(path).contains("- [" + title + "](../tasks/"),
                )
            }
        }
    }

    /**
     * Имя файла задачи детерминировано (требование среза): сухой прогон обещает ровно то, что
     * сделает настоящий. Порядок ключей карты приходит от GitHub и повторяться не обязан, поэтому
     * заметки обходятся отсортированными.
     */
    @Test
    fun `имена задач не зависят от порядка заметок в карте`() {
        val a = "встречи/2026-08-12-a.md"
        val b = "идеи/2026-08-12-b.md"
        val note = note(body = "**Задачи.**\n- [ ] одинаковое дело\n")
        val direct = Migration.plan(linkedMapOf(a to note, b to note)).made
        val reverse = Migration.plan(linkedMapOf(b to note, a to note)).made
        assertEquals(direct, reverse)
        assertEquals(
            listOf("tasks/2026-08-12-odinakovoe-delo.md", "tasks/2026-08-12-odinakovoe-delo-2.md"),
            direct.map { it.path },
        )
    }

    @Test
    fun `дату не из чего взять — заметка пропущена и названа, а не переписана наугад`() {
        val note = note(head = "", body = "**Задачи.**\n- [ ] дело\n").replace(RECORDED, "")
        val plan = Migration.plan(mapOf("идеи/без-даты.md" to note))
        assertTrue(plan.isEmpty)
        assertEquals(listOf("идеи/без-даты.md"), plan.skipped)
    }

    /**
     * Заметка с CRLF: переписанная строка должна остаться CRLF, иначе в файле заводятся смешанные
     * переводы строк — текст цел, но следующий дифф владельца показывает «изменилось всё».
     */
    @Test
    fun `у заметки с CRLF переводы строк не смешиваются`() {
        val path = "идеи/2026-08-12-a.md"
        val text = note(body = "**Задачи.**\n- [ ] дело\n").replace("\n", "\r\n")
        val after = put(Migration.plan(mapOf(path to text)), path)
        assertTrue(after, after.contains("- [дело](../tasks/2026-08-12-delo.md)\r\n"))
        assertEquals(text.count { it == '\r' }, after.count { it == '\r' })
    }

    /**
     * Вложенный чекбокс становится отдельной задачей верхнего уровня, а не подзадачей родителя:
     * родство задач миграция не переносит (заведено отдельно — `nikitatrubaev-0rk.28`). Текст при
     * этом цел — отступ строки сохраняется, вложенность видна в самой заметке.
     */
    @Test
    fun `вложенная подзадача — отдельная задача, но отступ строки цел`() {
        val path = "идеи/2026-08-12-a.md"
        val body = "**Задачи.**\n- [ ] дело\n  - [ ] подпункт\n"
        val plan = Migration.plan(mapOf(path to note(body = body)))
        assertEquals(listOf("дело", "подпункт"), plan.made.map { it.title })
        assertTrue(
            put(plan, path),
            put(plan, path).contains("\n  - [подпункт](../tasks/2026-08-12-podpunkt.md)"),
        )
    }

    private fun put(plan: Migration.Plan, path: String): String =
        plan.changes.filterIsInstance<BatchPlan.Put>().single { it.path == path }.content

    private fun note(head: String = "", body: String): String =
        "---\n$RECORDED${head}type: идея\ntitle: Заметка\nstatus: done\n---\n\n## Саммари\n$body"

    private companion object {
        const val RECORDED = "recorded: 2026-08-12T19:22:10+03:00\n"
    }
}
