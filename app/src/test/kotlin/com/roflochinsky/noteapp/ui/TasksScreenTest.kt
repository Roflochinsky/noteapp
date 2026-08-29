package com.roflochinsky.noteapp.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
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

/**
 * Экран задач через дерево семантики: то, что до этого среза ловили только глазами на ревью.
 *
 * Robolectric, а не `androidTest`: гейт офлайновый и без устройства, эти тесты идут в общем
 * `testDebugUnitTest`.
 *
 * **Грабли, стоившие одной ложной мутации:** высоту, которую задаёт текст, здесь мерить нельзя —
 * настоящего шрифта у Robolectric нет, и строка `bodySmall` (13sp) выходит 35dp вместо ~18dp на
 * устройстве. Проверка «тач-таргет ≥48dp» на такой строке проходит сама собой и ничего не сторожит.
 * Мерить можно там, где высоту задаёт `Modifier.height` (рубрика «Сделано»); остальное проверять
 * через семантику — есть ли клик, какая роль, какое описание.
 */
@RunWith(RobolectricTestRunner::class)
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
                    onTab = {},
                    onRefresh = {},
                    onRecord = {},
                    onSettings = { settingsOpened++ },
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

    private companion object {
        const val EMPTY = "Задач пока нет"
        const val NO_TOKEN = "нет GitHub-токена — тап, чтобы подключить"
        val TOUCH = 48.dp
    }
}
