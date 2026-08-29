package com.roflochinsky.noteapp.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.roflochinsky.noteapp.pipeline.Edit
import com.roflochinsky.noteapp.pipeline.TaskFile
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** Попадания и семантика деталки задачи: то, что ревью Н2 ловило чтением кода. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TaskDetailScreenTest {

    @get:Rule val compose = createComposeRule()

    private val today = LocalDate.of(2026, 8, 26)
    private var lastEdit: Edit? = null

    private fun task(status: String = TaskFile.STATUS_OPEN) =
        TaskFile.Task(
            path = "tasks/hleb.md",
            title = "Купить хлеб",
            status = status,
            created = LocalDate.of(2026, 8, 1),
            tags = listOf("дом"),
        )

    private fun detail(task: TaskFile.Task) {
        compose.setContent {
            DocTheme {
                TaskDetailScreen(
                    task = task,
                    projects = emptyList(),
                    pending = false,
                    today = today,
                    notice = null,
                    onNotice = {},
                    onEdit = { lastEdit = it },
                    onStatus = {},
                    onDelete = {},
                    onOpen = {},
                    onBack = {},
                )
            }
        }
    }

    /**
     * Имя тега — не кнопка удаления: удаление тега ничем не отменяется, целиться надо в видимый
     * крестик.
     *
     * Ловил `bd nikitatrubaev-0rk.21`: крестик нарисован на 12dp, а штатный near-hit Compose
     * растягивал его зону до 48dp, и центр короткого имени `#дом` (146,5px при зоне 145..193) в неё
     * попадал. Закрыто урезанием мазка до 24dp в ряду тегов (`DrawnTouch`), зона крестика —
     * 157..181. Тест писан против ПОВЕДЕНИЯ и переживёт смену реализации.
     */
    @Test
    fun `тап по имени тега не удаляет тег`() {
        detail(task())
        compose.onNodeWithText("#дом").performClick()
        assertNull(lastEdit)
    }

    /**
     * Зазор между рядами тегов (`bd nikitatrubaev-0rk.18`): настоящего попадания в нём нет ни у
     * кого, поэтому единственная защита — чтобы зона крестика не вылезала за нарисованный чип.
     * Радиус мазка симметричен (`max(0, (24 − 12)/2)` = 6dp на сторону), поэтому меряется он над
     * чипом: 10dp выше крестика — это уже вне чипа, но втрое ближе прежнего мазка в 18dp. Под чипом
     * ту же границу не измерить: ряд тегов стоит у нижней кромки прокрутки, и всё, что ниже,
     * отсекает её клип — тест был бы зелёным при любом радиусе.
     */
    @Test
    fun `тап над чипом тег не удаляет`() {
        detail(task())
        val cross = compose.onNodeWithContentDescription("убрать тег #дом").getBoundsInRoot()
        with(compose.density) {
            val x = (cross.left.toPx() + cross.right.toPx()) / 2
            compose.onRoot().performTouchInput { click(Offset(x, cross.top.toPx() - 10.dp.toPx())) }
        }
        assertNull(lastEdit)
    }

    /** Контроль к предыдущим: без него «ничего не произошло» проходило бы и на мёртвом крестике. */
    @Test
    fun `тап по крестику удаляет тег`() {
        detail(task())
        compose.onNodeWithContentDescription("убрать тег #дом").performClick()
        assertEquals(Edit.SetField("tags", null), lastEdit)
    }

    /**
     * Сегмент статуса — переключатель, а не три подписи: на слух `in_progress` иначе неотличим от
     * соседей. TalkBack читает роль и «выбрано», поэтому проверяется и то, и другое.
     */
    @Test
    fun `сегмент статуса — радиокнопка и знает, что выбрано`() {
        detail(task(status = TaskFile.STATUS_IN_PROGRESS))
        compose
            .onNodeWithText("В работе")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assertIsSelected()
        compose.onNodeWithText("Открыта").assertIsNotSelected()
    }
}
