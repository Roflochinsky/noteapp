package com.roflochinsky.noteapp.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Новый проект прямо из шторки (комп v2, борд 2: «Новый проект · запишется в projects.md»).
 * Свободный ввод убирали в Н2 (пункт 26 плана) ровно потому, что рождался проект без реестра;
 * теперь ввод ведёт в реестр — `bd nikitatrubaev-0rk.23`.
 *
 * **`@GraphicsMode(NATIVE)` обязателен**: без него у Robolectric нет шрифта и метрики врут.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NewTaskSheetTest {

    @get:Rule val compose = createComposeRule()

    private var created: String? = null
    private var draft: NewTask? = null

    private fun sheet(projects: List<String> = listOf("tgsum")) {
        compose.setContent {
            DocTheme {
                NewTaskSheet(
                    projects = projects,
                    today = LocalDate.parse("2026-08-26"),
                    taken = emptySet(),
                    onDismiss = {},
                    onNewProject = { created = it },
                    onCreate = { draft = it },
                )
            }
        }
    }

    private fun type(name: String) {
        compose.onNodeWithText("Без проекта").performClick()
        compose.onNodeWithText("Новый проект").performClick()
        compose.onNode(hasSetTextAction()).performTextInput(name)
        compose.onNodeWithText("Добавить").performClick()
    }

    @Test
    fun `шторка обещает реестр и заводит в нём проект`() {
        sheet()
        compose.onNodeWithText("Без проекта").performClick()
        compose.onNodeWithText("Новый проект").assertIsDisplayed()
        compose.onNodeWithText("запишется в projects.md").assertIsDisplayed()
        compose.onNodeWithText("Новый проект").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("voicebox")
        compose.onNodeWithText("Добавить").performClick()
        assertEquals("voicebox", created)
        compose.onNodeWithText("voicebox").assertIsDisplayed()
    }

    /**
     * Имя уже есть в реестре — заводить второе такое же нельзя: это два значения чипа с одним
     * смыслом. Владелец получает то, что в реестре уже стоит, регистр его ввода не в счёт.
     */
    @Test
    fun `такой проект уже есть — берётся он, а не второй такой же`() {
        sheet()
        type("TGSum")
        assertNull("завели дубликат tgsum", created)
        compose.onNodeWithText("tgsum").assertIsDisplayed()
    }

    /** Пояснение после тире — часть строки реестра, а не имени: иначе чип разъедется с файлом. */
    @Test
    fun `пояснение после тире в имя проекта не попадает`() {
        sheet()
        type("voicebox — второй диктофон")
        assertEquals("voicebox", created)
        assertNull(draft)
    }
}
