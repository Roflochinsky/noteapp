package com.roflochinsky.noteapp.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import com.roflochinsky.noteapp.pipeline.FeedItem
import com.roflochinsky.noteapp.pipeline.NoteFile
import com.roflochinsky.noteapp.pipeline.NoteRef
import com.roflochinsky.noteapp.pipeline.NotesStore
import com.roflochinsky.noteapp.pipeline.SyncStatus
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Лента v2 (борд 5 компа) через дерево семантики: склейка «запись ∪ заметка репо», чипы, поиск.
 *
 * `@GraphicsMode(NATIVE)` обязателен: без него у Robolectric нет шрифта, текст меряется как 1px на
 * символ, и любая проверка геометрии проходит вхолостую (см. `TasksScreenTest`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeedScreenTest {

    @get:Rule val compose = createComposeRule()

    private val today = LocalDate.of(2026, 8, 26)
    private var opened = ""

    /** Заметка из репо: поля frontmatter картой — их больше, чем разумно держать аргументами. */
    private fun repoNote(
        path: String,
        recorded: String,
        title: String,
        fields: Map<String, String> = emptyMap(),
        summary: String = "Суть записи",
    ): NoteFile.Note {
        val md = buildString {
            append("---\nrecorded: $recorded\nduration: 12:31\ntitle: $title\n")
            fields.forEach { (key, value) -> append("$key: $value\n") }
            append("status: done\n---\n\n## Саммари\n**Суть.** $summary\n")
        }
        return checkNotNull(NoteFile.parse(path, md))
    }

    private val meeting =
        repoNote(
            path = "встречи/2026-08-24-1807-reliz.md",
            recorded = "2026-08-24T18:07:32+03:00",
            title = "Созвон с Димой — релиз tgsum",
            fields = mapOf("type" to "встреча", "participants" to "[Дима]"),
            summary = "Релиз назначен на пятницу.",
        )

    private val idea =
        repoNote(
            path = "идеи/2026-08-12-1922-notion.md",
            recorded = "2026-08-12T19:22:10+03:00",
            title = "Идея про экспорт в Notion",
            fields = mapOf("type" to "идея"),
            summary = "Экспорт прямо в Notion.",
        )

    /** Локальная запись той же встречи: телефон её уже расшифровал и отправил. */
    private fun record(id: String, pushed: Boolean = true, transcribed: Boolean = true) =
        NotesStore.Note(
            id = id,
            hasAudio = true,
            transcribed = transcribed,
            pushed = pushed,
            durationSec = 751,
            title = "Смотри, по релизу",
            preview = "Смотри, по релизу: я бы закрыл экспорт тем",
        )

    private fun screen(feed: List<FeedItem>, people: List<String> = emptyList()) {
        compose.setContent {
            DocTheme {
                FeedScreen(
                    feed = feed,
                    people = people,
                    projects = emptyList(),
                    today = today,
                    isRecording = false,
                    tasksCount = 0,
                    sync = SyncStatus.OK,
                    refreshing = false,
                    onTab = {},
                    onNote = { opened = it },
                    onRefresh = {},
                    onRecord = {},
                    onSettings = {},
                )
            }
        }
    }

    /** Принцип 4: та же заметка с телефона и из репо — одна строка, а не две. */
    @Test
    fun `запись и её файл в репо — одна строка ленты`() {
        screen(NoteRef.merge(listOf(record("20260824-180732")), listOf(meeting)))
        compose.onNodeWithText("Созвон с Димой — релиз tgsum").assertExists()
        compose.onNodeWithText("Смотри, по релизу").assertDoesNotExist()
        compose.onNodeWithText("✓ в GitHub").assertExists()
    }

    /** Запись без сети из ленты не пропадает и честно говорит, что ещё не уехала. */
    @Test
    fun `запись без сети видна со статусом`() {
        screen(NoteRef.merge(listOf(record("20260826-120000", pushed = false)), emptyList()))
        compose.onNodeWithText("Смотри, по релизу").assertExists()
        compose.onNodeWithText("ждёт отправки").assertExists()
    }

    /** Очередь расшифровки — янтарь, как и вся очередь мира (вердикт UX: было синим). */
    @Test
    fun `нерасшифрованная запись говорит про очередь`() {
        screen(
            NoteRef.merge(
                listOf(record("20260826-120000", pushed = false, transcribed = false)),
                emptyList(),
            )
        )
        compose.onNodeWithText("в очереди — расшифровка").assertExists()
    }

    @Test
    fun `тап по строке открывает заметку по её ref`() {
        screen(NoteRef.merge(emptyList(), listOf(meeting)))
        compose.onNodeWithText("Созвон с Димой — релиз tgsum").performClick()
        assertEquals("20260824-1807", opened)
    }

    @Test
    fun `чип типа сужает ленту`() {
        screen(NoteRef.merge(emptyList(), listOf(meeting, idea)))
        compose.onNodeWithText("Тип").performClick()
        compose.onNode(hasText("идея") and hasText("1")).performClick()
        compose.onNodeWithText("Идея про экспорт в Notion").assertExists()
        compose.onNodeWithText("Созвон с Димой — релиз tgsum").assertDoesNotExist()
    }

    /** Комп, борд 5: активный чип персоны называет ось — «Персона: Дима», не просто «Дима». */
    @Test
    fun `чип персоны называет ось и снимается крестиком`() {
        screen(NoteRef.merge(emptyList(), listOf(meeting, idea)), people = listOf("Дима"))
        compose.onNodeWithText("Персона").performClick()
        compose.onNode(hasText("Дима") and hasText("1")).performClick()
        compose.onNodeWithText("Персона: Дима").assertExists()
        compose.onNodeWithText("Идея про экспорт в Notion").assertDoesNotExist()
        compose.onNodeWithContentDescription("Сбросить Персона: Дима").performClick()
        compose.onNodeWithText("Идея про экспорт в Notion").assertExists()
    }

    /** Решение владельца (а): поиск по ленте ищет и в заголовке, и в превью. */
    @Test
    fun `поиск в шапке сужает ленту по превью`() {
        screen(NoteRef.merge(emptyList(), listOf(meeting, idea)))
        compose.onNodeWithContentDescription("Поиск").performClick()
        compose.onNodeWithText("Поиск по заметкам").assertExists()
        compose.onNode(hasSetTextAction()).performTextInput("пятницу")
        compose.onNodeWithText("Созвон с Димой — релиз tgsum").assertExists()
        compose.onNodeWithText("Идея про экспорт в Notion").assertDoesNotExist()
    }

    @Test
    fun `под фильтром без совпадений экран говорит про фильтр, а не про пустую ленту`() {
        screen(NoteRef.merge(emptyList(), listOf(meeting)))
        compose.onNodeWithText("Тип").performClick()
        compose.onNodeWithText("личное").performClick()
        compose.onNodeWithText("Пока ни одной заметки").assertDoesNotExist()
        compose.onNodeWithText("Под этот фильтр ничего не подошло").assertExists()
        compose.onNodeWithText("Сбросить фильтры").performClick()
        compose.onNodeWithText("Созвон с Димой — релиз tgsum").assertExists()
    }

    @Test
    fun `пустая лента говорит, что заметок нет`() {
        screen(emptyList())
        compose.onNodeWithText("Пока ни одной заметки").assertExists()
    }

    /** Пять чипов компа (борд 5) и их порядок: строка прокручивается, ни один не потерян. */
    @Test
    fun `в ленте пять чипов в порядке компа`() {
        screen(NoteRef.merge(emptyList(), listOf(meeting)))
        val chips =
            compose.onNode(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)
            )
        listOf("Тип", "Персона", "Проект", "Тег", "Дата").forEachIndexed { i, name ->
            chips.performScrollToIndex(i)
            compose.onNodeWithText(name).assertExists()
        }
    }

    /** Рубрика дня — из времени записи, а не из порядка строк. */
    @Test
    fun `строки собраны рубриками дней`() {
        screen(NoteRef.merge(emptyList(), listOf(meeting, idea)))
        compose.onNodeWithText("24 АВГУСТА").assertExists()
        compose.onNodeWithText("12 АВГУСТА").assertExists()
    }
}
