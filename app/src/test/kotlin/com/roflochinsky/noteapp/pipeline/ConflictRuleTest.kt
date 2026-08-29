package com.roflochinsky.noteapp.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Трёхстороннее слияние на 409 (решение LLD-2): разные поля — сливаем молча, одно и то же —
 * побеждает git и владельцу показывается расхождение.
 *
 * Правило отдаёт вердикт, файл собирает переигрывание правки в `RepoStore` — поэтому здесь
 * проверяется только вердикт, а сам слитый файл — в `RepoStoreWriteTest`.
 */
class ConflictRuleTest {

    private val base =
        """
        ---
        title: Фикс ретраев очереди
        project: tgsum
        priority: P1
        status: open
        created: 2026-08-25
        ---

        Описание из заметки.

        ## Подзадачи
        - [ ] Воспроизвести баг
        - [ ] Бэкофф в PushWorker
        """
            .trimIndent()

    private fun task(text: String) = TaskFile.parse(PATH, text)

    private fun resolve(mine: String, theirs: String) =
        ConflictRule.resolve(task(base), task(mine), task(theirs))

    @Test
    fun `мы приоритет, они срок — сливается молча`() {
        val mine = Edit.apply(base, Edit.SetField("priority", "P3"))
        val theirs = Edit.apply(base, Edit.SetField("due", "2026-08-30"))
        assertEquals(ConflictRule.Merged, resolve(mine, theirs))
    }

    @Test
    fun `оба тронули статус — расхождение, победил git`() {
        val mine = Edit.apply(base, Edit.SetField("status", TaskFile.STATUS_DONE))
        val theirs = Edit.apply(base, Edit.SetField("status", TaskFile.STATUS_IN_PROGRESS))
        val out = resolve(mine, theirs) as ConflictRule.Divergence
        assertEquals(listOf("status"), out.fields)
    }

    @Test
    fun `одинаковая правка с обеих сторон конфликтом не считается`() {
        val same = Edit.apply(base, Edit.SetField("status", TaskFile.STATUS_DONE))
        assertEquals(ConflictRule.Merged, resolve(same, same))
    }

    /** Строки-чекбоксы в описание не входят: перестановка и чужая подзадача — не расхождение. */
    @Test
    fun `чужая правка подзадач расхождением не считается`() {
        val mine = Edit.apply(base, Edit.ToggleSubtask("Бэкофф в PushWorker", true))
        val theirs =
            Edit.apply(
                base.replace(
                    "- [ ] Воспроизвести баг\n- [ ] Бэкофф в PushWorker",
                    "- [ ] Бэкофф в PushWorker\n- [ ] Воспроизвести баг",
                ),
                Edit.AddSubtask("Тест на потерю сети"),
            )
        assertEquals(ConflictRule.Merged, resolve(mine, theirs))
    }

    @Test
    fun `описание неделимо — правка с двух сторон это расхождение`() {
        val mine = base.replace("Описание из заметки.", "Моё описание.")
        val theirs = base.replace("Описание из заметки.", "Саммари от Claude.")
        val out = resolve(mine, theirs) as ConflictRule.Divergence
        assertEquals(listOf(ConflictRule.DESCRIPTION), out.fields)
    }

    @Test
    fun `новое поле с одной стороны расхождением не считается`() {
        val mine = Edit.apply(base, Edit.AddSubtask("Тест на потерю сети"))
        val theirs = Edit.apply(base, Edit.SetField("priority", "P2"))
        assertEquals(ConflictRule.Merged, resolve(mine, theirs))
    }

    private companion object {
        const val PATH = "tasks/2026-08-25-fix-retraev.md"
    }
}
