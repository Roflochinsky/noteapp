package com.roflochinsky.noteapp.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Заголовок шторки выбора против компа v2 (`.sheet .title`, борды 2 и 4): обычный регистр и голос
 * заголовка, а не капс-рубрика.
 *
 * **`@GraphicsMode(NATIVE)` обязателен**: без него у Robolectric нет шрифта, текст меряется как 1px
 * на символ, и всё, что опирается на метрики, проходит вхолостую.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TaskSheetsTest {

    @get:Rule val compose = createComposeRule()

    @Before
    fun sheet() {
        compose.setContent {
            DocTheme {
                ChoiceSheet(
                    title = "Проект",
                    choices = listOf(Choice("tgsum", "tgsum")),
                    selected = null,
                    onDismiss = {},
                    onPick = {},
                )
            }
        }
    }

    /** Ловит `bd nikitatrubaev-0rk.26`: до среза узел назывался «ПРОЕКТ». */
    @Test
    fun `заголовок шторки набран обычным регистром`() {
        compose.onNodeWithText("Проект").assertIsDisplayed()
    }

    /**
     * Регистра мало: капс-рубрика 11sp в обычном регистре осталась бы рубрикой. Кегль сравнивается
     * со строкой выбора, а не с числом, чтобы будущий срез «шторке нужна своя роль Headline» тест
     * не ломал.
     */
    @Test
    fun `заголовок шторки крупнее строк выбора`() {
        val title = fontSizeSp(compose.onNodeWithText("Проект"))
        val option = fontSizeSp(compose.onNodeWithText("tgsum"))
        assertTrue("заголовок $title sp, строка выбора $option sp", title > option)
    }

    /**
     * Ловит `bd nikitatrubaev-0rk.27`: до среза заголовок брал `headlineSmall` 24sp — это Display
     * компа (переключатель «Заметки | Задачи»). Комп v2 `.sheet .title` — 1.15rem = 18.4px, роль
     * DESIGN.md — Headline ступенью ниже, в теме это 19sp (`OverlayHeadline`).
     */
    @Test
    fun `заголовок шторки набран Headline ступенью ниже, а не Display`() {
        assertEquals(19f, fontSizeSp(compose.onNodeWithText("Проект")), 0.01f)
    }

    private fun fontSizeSp(node: SemanticsNodeInteraction): Float {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.first().layoutInput.style.fontSize.value
    }
}
