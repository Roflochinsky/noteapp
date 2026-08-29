package com.roflochinsky.noteapp.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
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

    private fun task(
        title: String,
        status: String = TaskFile.STATUS_OPEN,
        done: String? = null,
        project: String? = null,
        priority: String = "P2",
    ) =
        TaskFile.Task(
            path = "tasks/$title.md",
            title = title,
            priority = priority,
            status = status,
            project = project,
            created = LocalDate.of(2026, 8, 1),
            done = done?.let(LocalDate::parse),
        )

    /** `setContent` рулём зовётся ровно один раз, поэтому статус синка живёт состоянием. */
    private fun screen(tasks: List<TaskFile.Task>, projects: List<String> = emptyList()) {
        compose.setContent {
            DocTheme {
                TasksScreen(
                    tasks = tasks,
                    projects = projects,
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

    // ── чипы, шторки и поиск (срез Н3) ────────────────────────────────────────────────────

    /** Чип сужает список: выбранное значение остаётся, чужие уходят. */
    @Test
    fun `чип проекта сужает список`() {
        screen(
            listOf(task("Фикс ретраев", project = "tgsum"), task("Разобрать фото")),
            projects = listOf("tgsum"),
        )
        compose.onNodeWithText("Проект").performClick()
        // «tgsum» есть и в мете строки задачи — строку шторки отличает счётчик рядом.
        compose.onNode(hasText("tgsum") and hasText("1")).performClick()
        compose.onNodeWithText("Фикс ретраев").assertExists()
        compose.onNodeWithText("Разобрать фото").assertDoesNotExist()
    }

    /**
     * Счётчики в шторке — фасетные (вердикт UX): свой чип из расчёта исключён, а значение реестра
     * без задач остаётся со счётчиком 0 и из шторки не пропадает.
     */
    @Test
    fun `в шторке проекта стоят счётчики, включая ноль у пустого проекта`() {
        screen(
            listOf(task("Фикс ретраев", project = "tgsum"), task("Разобрать фото")),
            projects = listOf("tgsum", "workwatch"),
        )
        compose.onNodeWithText("Проект").performClick()
        compose.onNodeWithText("Все проекты").assertExists()
        compose.onNodeWithText("workwatch").assertExists()
        compose.onAllNodesWithText("0").assertCountEquals(1)
    }

    /**
     * Пусто под фильтром — не то же, что «задач нет вовсе»: задачи есть, просто не подошли, и выход
     * из этого состояния должен быть одним тапом.
     */
    @Test
    fun `под фильтром без совпадений экран говорит про фильтр, а не про пустой трекер`() {
        screen(listOf(task("Фикс ретраев", priority = "P1")), projects = emptyList())
        compose.onNodeWithText("Приоритет").performClick()
        compose.onNodeWithText("P3 · низкий").performClick()
        compose.onNodeWithText(EMPTY).assertDoesNotExist()
        compose.onNodeWithText(NOTHING).assertExists()
        compose.onNodeWithText("Сбросить фильтры").performClick()
        compose.onNodeWithText("Фикс ретраев").assertExists()
    }

    /** Поиск — лупа в шапке, поле разворачивается на месте вкладок и сужает текущий список. */
    @Test
    fun `лупа разворачивает поле поиска и сужает список`() {
        screen(listOf(task("Фикс ретраев"), task("Разобрать фото")))
        compose.onNodeWithContentDescription("Поиск").performClick()
        compose.onNodeWithText("Поиск по задачам").assertExists()
        compose.onNode(hasSetTextAction()).performTextInput("фото")
        compose.onNodeWithText("Разобрать фото").assertExists()
        compose.onNodeWithText("Фикс ретраев").assertDoesNotExist()
        compose.onNodeWithContentDescription("Закрыть поиск").performClick()
        compose.onNodeWithText("Фикс ретраев").assertExists()
    }

    // ── чипы «Тег» и «Срок» (комп v2, борд 1; `bd nikitatrubaev-0rk.25`) ──────────────────

    /**
     * Пять чипов компа в строку не влезают, и `LazyRow` два последних до прокрутки не компонует —
     * без этого «Тег» и «Срок» в дереве семантики просто не существуют. Прокручиваем именно строку
     * чипов: список задач под ней — тоже прокручиваемый узел, и `hasScrollAction()` берёт оба.
     * Якорь — чип «Приоритет», поэтому звать до того, как строку увели вправо.
     */
    private fun openChip(name: String) {
        compose
            .onNode(hasScrollAction() and hasAnyDescendant(hasText("Приоритет")))
            .performScrollToNode(hasText(name))
        compose.onNodeWithText(name).performClick()
    }

    /** Тег в задаче не один, поэтому чип сужает по вхождению, а не по первому тегу. */
    @Test
    fun `чип тега сужает список и снимается своим крестиком`() {
        screen(
            listOf(
                task("Фикс ретраев").copy(tags = listOf("релиз", "деньги")),
                task("Разобрать фото").copy(tags = listOf("личное")),
            )
        )
        openChip("Тег")
        // «#деньги» есть и в мете строки задачи — строку шторки отличает счётчик рядом.
        compose.onNode(hasText("#деньги") and hasText("1")).performClick()
        compose.onNodeWithText("Фикс ретраев").assertExists()
        compose.onNodeWithText("Разобрать фото").assertDoesNotExist()
        compose.onNodeWithContentDescription("Сбросить #деньги").performClick()
        compose.onNodeWithText("Разобрать фото").assertExists()
    }

    /** Реестра тегов нет по ADR: значения шторки собираются из задач, порядок — алфавитный. */
    @Test
    fun `в шторке тега стоят счётчики, значения собраны из задач`() {
        screen(
            listOf(
                task("Фикс ретраев").copy(tags = listOf("релиз")),
                task("Экспорт тем").copy(tags = listOf("релиз")),
                task("Разобрать фото").copy(tags = listOf("личное")),
            )
        )
        openChip("Тег")
        compose.onNodeWithText("Все теги").assertExists()
        compose.onNode(hasText("#релиз") and hasText("2")).assertExists()
        compose.onNode(hasText("#личное") and hasText("1")).assertExists()
    }

    /** Срок — не значение, а окно: шторка предлагает четыре окна, а не даты задач. */
    @Test
    fun `чип срока сужает список окном и снимается своим крестиком`() {
        screen(
            listOf(
                task("Фикс ретраев").copy(due = LocalDate.of(2026, 8, 28)),
                task("Разобрать фото"),
            )
        )
        openChip("Срок")
        compose.onNodeWithText("На неделе").performClick()
        compose.onNodeWithText("Фикс ретраев").assertExists()
        compose.onNodeWithText("Разобрать фото").assertDoesNotExist()
        compose.onNodeWithContentDescription("Сбросить На неделе").performClick()
        compose.onNodeWithText("Разобрать фото").assertExists()
    }

    /** Окно без задач из шторки не пропадает, а показывает 0 — как значение реестра у проекта. */
    @Test
    fun `в шторке срока стоят счётчики, включая ноль у пустого окна`() {
        screen(
            listOf(
                task("Фикс ретраев").copy(due = LocalDate.of(2026, 8, 28)),
                task("Разобрать фото"),
            )
        )
        openChip("Срок")
        compose.onNode(hasText("Любой срок") and hasText("2")).assertExists()
        compose.onNode(hasText("На неделе") and hasText("1")).assertExists()
        compose.onNode(hasText("Без срока") and hasText("1")).assertExists()
        // «Сегодня» и «Просроченные» пусты — но строки на месте.
        compose.onAllNodesWithText("0").assertCountEquals(2)
    }

    /** Активный чип показывает выбранное значение и снимается своим крестиком (комп, борд 1). */
    @Test
    fun `крестик активного чипа сбрасывает фильтр`() {
        screen(listOf(task("Фикс ретраев", project = "tgsum"), task("Разобрать фото")))
        compose.onNodeWithText("Проект").performClick()
        compose.onNodeWithText("Без проекта").performClick()
        compose.onNodeWithText("Фикс ретраев").assertDoesNotExist()
        compose.onNodeWithContentDescription("Сбросить Без проекта").performClick()
        compose.onNodeWithText("Фикс ретраев").assertExists()
    }

    private companion object {
        const val EMPTY = "Задач пока нет"
        const val NO_TOKEN = "нет GitHub-токена — тап, чтобы подключить"
        const val NOTHING = "Под этот фильтр ничего не подошло"
        val TOUCH = 48.dp
    }
}
