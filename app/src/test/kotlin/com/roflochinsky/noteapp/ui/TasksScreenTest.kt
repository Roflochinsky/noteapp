package com.roflochinsky.noteapp.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.roflochinsky.noteapp.pipeline.SyncStatus
import com.roflochinsky.noteapp.pipeline.TaskFile
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Экран задач через дерево семантики: то, что до этого среза ловили только глазами на ревью.
 *
 * Robolectric, а не `androidTest`: гейт офлайновый и без устройства, эти тесты идут в общем
 * `testDebugUnitTest`.
 *
 * **`@GraphicsMode(NATIVE)` обязателен** и стоит здесь не для красоты: в режиме по умолчанию у
 * Robolectric нет шрифта, и текст меряется как 1px на символ («#дом» — 4px вместо 33px). Любая
 * проверка геометрии — тач-таргет, попадание, перенос — на таких метриках либо врёт, либо проходит
 * сама собой; одна ложная мутация на этом уже потеряна.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TasksScreenTest {

    @get:Rule val compose = createComposeRule()

    private val today = LocalDate.of(2026, 8, 26)
    private val sync = mutableStateOf(SyncStatus.OK)
    private var settingsOpened = 0

    private fun task(title: String, status: String = TaskFile.STATUS_OPEN, done: String? = null) =
        TaskFile.Task(
            path = "tasks/$title.md",
            title = title,
            status = status,
            created = LocalDate.of(2026, 8, 1),
            done = done?.let(LocalDate::parse),
        )

    /** `setContent` рулём зовётся ровно один раз, поэтому статус синка живёт состоянием. */
    private fun screen(tasks: List<TaskFile.Task>) {
        compose.setContent {
            DocTheme {
                TasksScreen(
                    tasks = tasks,
                    today = today,
                    sync = sync.value,
                    refreshing = false,
                    isRecording = false,
                    notice = null,
                    pending = { false },
                    onTab = {},
                    onRefresh = {},
                    onRecord = {},
                    onSettings = { settingsOpened++ },
                    onTask = {},
                    onNewTask = {},
                    onToggle = { "" },
                    onCancel = {},
                    onFlush = {},
                    onNotice = {},
                )
            }
        }
    }

    /** Контроль к следующему тесту: без него «текста нет» прошло бы и на пустом экране. */
    @Test
    fun `пустой экран говорит, что задач нет`() {
        screen(emptyList())
        compose.onNodeWithText(EMPTY).assertExists()
    }

    /** Вердикт UX: «Задач пока нет» рядом с рубрикой «Сделано» — враньё экрана. */
    @Test
    fun `сделанные в окне месяца отменяют пустое состояние`() {
        screen(listOf(task("Сделана вчера", TaskFile.STATUS_DONE, done = "2026-08-25")))
        compose.onNodeWithText(EMPTY).assertDoesNotExist()
    }

    /**
     * Тач-таргет 48dp — правило доступности, чинившееся в Н1 и Н2 по разным местам.
     *
     * Открытая задача в списке нужна не для красоты: без неё тест ловил бы заодно и поломку пустого
     * состояния (иллюстрация выдавливает рубрику за экран, а `LazyColumn` невидимое не собирает) —
     * одна мутация убивала два теста.
     */
    @Test
    fun `рубрика Сделано держит тач-таргет 48dp`() {
        screen(
            listOf(
                task("Открытая"),
                task("Сделана вчера", TaskFile.STATUS_DONE, done = "2026-08-25"),
            )
        )
        compose.onNodeWithText("Сделано за месяц · 1").assertHeightIsAtLeast(TOUCH)
    }

    /**
     * Строка синка — единственная поверхность для ошибок синка, и в настройки она ведёт только
     * тогда, когда там есть что чинить.
     *
     * Проверяется не отключённый `OnClick`, а флаг `Disabled`: снятый `clickable` действие из
     * семантики не убирает, он его гасит — и именно это озвучивает TalkBack.
     */
    @Test
    fun `строка синка ведёт в настройки только при отсутствии токена`() {
        sync.value = SyncStatus.OFFLINE
        screen(emptyList())
        compose.onNodeWithText("нет сети — показан кэш").assertIsNotEnabled()

        sync.value = SyncStatus.NO_TOKEN
        compose.onNodeWithText(NO_TOKEN).assertIsEnabled().performClick()
        assertEquals(1, settingsOpened)
    }

    /**
     * Строка синка кликабельна — значит и её тач-таргет 48dp; чинилось в Н1, тестом не держалось.
     */
    @Test
    fun `строка синка держит тач-таргет 48dp`() {
        sync.value = SyncStatus.NO_TOKEN
        screen(emptyList())
        compose.onNodeWithText(NO_TOKEN).assertHeightIsAtLeast(TOUCH)
    }

    /**
     * TalkBack должен читать «флажок, не отмечено, Купить хлеб». Состояние добавляет сама роль; в
     * описании его быть не должно — прежнее «сделана: $title» стояло и на неотмеченном флажке, то
     * есть врало ровно там, где владелец проверить не может (находка Д11 ревью Н2).
     */
    @Test
    fun `чекбокс задачи — флажок с описанием без состояния`() {
        screen(listOf(task("Купить хлеб")))
        compose
            .onNode(isToggleable())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
            .assertContentDescriptionEquals("Купить хлеб")
    }

    private companion object {
        const val EMPTY = "Задач пока нет"
        const val NO_TOKEN = "нет GitHub-токена — тап, чтобы подключить"
        val TOUCH = 48.dp
    }
}
