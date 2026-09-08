package com.roflochinsky.noteapp.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.roflochinsky.noteapp.pipeline.Edit
import com.roflochinsky.noteapp.pipeline.FeedItem
import com.roflochinsky.noteapp.pipeline.NoteFile
import com.roflochinsky.noteapp.pipeline.NoteRef
import com.roflochinsky.noteapp.pipeline.NotesStore
import com.roflochinsky.noteapp.pipeline.TaskFile
import com.roflochinsky.noteapp.pipeline.TranscriptMapper
import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

/**
 * Деталка заметки v2 (борд 6 компа): поля-чипы правятся, задачи — ссылками, транскрипт только
 * читается. Долг Н1 закрыт здесь же: экран получает заметку готовой и в сеть не ходит — проверяется
 * тем, что тесту не нужны ни токен, ни клиент.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DetailScreenTest {

    @get:Rule val compose = createComposeRule()

    private var edit: Edit? = null
    private var opened: String? = null
    private var task: String? = null
    private var retried = 0

    private val path = "встречи/2026-08-24-1807-reliz.md"

    private val md =
        """
        ---
        recorded: 2026-08-24T18:07:32+03:00
        duration: 12:31
        device: OnePlus 13
        type: встреча
        participants: [Дима]
        project: tgsum
        tags: [релиз]
        title: Созвон с Димой — релиз tgsum
        status: done
        ---

        ## Саммари
        **Суть.** Релиз назначен на пятницу.

        **Задачи.**
        - [Фикс ретраев очереди](../tasks/2026-08-25-fix.md)

        ## Транскрипт
        [00:12] Спикер 1: по релизу я бы закрыл экспорт тем
        """
            .trimIndent()

    private fun item(local: NotesStore.Note? = null, note: String? = md): FeedItem {
        val parsed = note?.let { checkNotNull(NoteFile.parse(path, it)) }
        return FeedItem(parsed?.let(NoteRef::of) ?: "20260824-1807", local, parsed)
    }

    private val chore =
        TaskFile.Task(
            path = "tasks/2026-08-25-fix.md",
            title = "Фикс ретраев очереди",
            priority = "P1",
            source = path,
            created = LocalDate.of(2026, 8, 25),
        )

    private fun screen(
        item: FeedItem = item(),
        tasks: List<TaskFile.Task> = listOf(chore),
        people: List<String> = listOf("Дима", "Оля"),
        pending: Boolean = false,
    ) {
        compose.setContent {
            DocTheme {
                DetailScreen(
                    item = item,
                    people = people,
                    projects = listOf("tgsum", "workwatch"),
                    tasks = tasks,
                    pending = pending,
                    onEdit = { edit = it },
                    onOpen = { opened = it },
                    onTask = { task = it },
                    onRetry = { retried++ },
                    onBack = {},
                )
            }
        }
    }

    @Test
    fun `поля заметки стоят чипами в шапке`() {
        screen()
        compose.onNodeWithText("Созвон с Димой — релиз tgsum").assertExists()
        compose.onNodeWithText("встреча").assertExists()
        compose.onNodeWithText("tgsum").assertExists()
        compose.onNodeWithText("Дима").assertExists()
        compose.onNodeWithText("#релиз").assertExists()
    }

    @Test
    fun `правка типа уходит правкой поля`() {
        screen()
        compose.onNodeWithText("встреча").performClick()
        compose.onNodeWithText("идея").performClick()
        assertEquals(Edit.SetField("type", "идея"), edit)
    }

    @Test
    fun `правка проекта уходит правкой поля`() {
        screen()
        compose.onNodeWithText("tgsum").performClick()
        compose.onNodeWithText("workwatch").performClick()
        assertEquals(Edit.SetField("project", "workwatch"), edit)
    }

    /**
     * Снятие поля. «Без проекта» — не «ничего не делать»: это `SetField(project, null)`, то есть
     * ключ уходит из frontmatter («поле опускается, если его нет», ADR). Пока теста не было, правка
     * «только при непустом значении» проходила гейт зелёной (мутация ревью Н5).
     */
    @Test
    fun `снятие проекта уходит правкой с пустым значением`() {
        screen()
        compose.onNodeWithText("tgsum").performClick()
        compose.onNodeWithText("Без проекта").performClick()
        assertEquals(Edit.SetField("project", null), edit)
    }

    /** Участники — выбор из реестра `people.md` плюс те, кого Claude услышал в записи. */
    @Test
    fun `участник добавляется из реестра списком`() {
        screen()
        compose.onNodeWithText("Дима").performClick()
        compose.onNodeWithText("Оля").performClick()
        assertEquals(Edit.SetField("participants", "[Дима, Оля]"), edit)
    }

    /** Карандаш переименования персоны рисуется здесь, а включается только в Н8 (вердикт UX). */
    @Test
    fun `карандаш персоны нарисован, но выключен до Н8`() {
        screen()
        compose.onNodeWithText("Дима").performClick()
        compose.onNodeWithContentDescription("Переименовать Оля").assertIsNotEnabled()
    }

    /** Правка транскрипта — эпик v3 (`nikitatrubaev-7cy`), здесь его только читают. */
    @Test
    fun `транскрипт не редактируется`() {
        screen()
        compose.onNodeWithText("Транскрипт").performClick()
        compose.onNodeWithText("по релизу я бы закрыл экспорт тем").assertExists()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    /** Статусы живут в задачах — в заметке только ссылки (комп, борд 6). */
    @Test
    fun `задачи заметки показаны ссылками`() {
        screen()
        compose.onNodeWithText("Фикс ретраев очереди").performClick()
        assertEquals("tasks/2026-08-25-fix.md", task)
    }

    @Test
    fun `чужие задачи в заметку не попадают`() {
        screen(tasks = listOf(chore.copy(path = "tasks/чужая.md", source = "идеи/другая.md")))
        compose.onNodeWithText("Фикс ретраев очереди").assertDoesNotExist()
    }

    /** Статус-строка компа: путь файла и напоминание про транскрипт. */
    @Test
    fun `статус-строка называет файл в репо`() {
        screen()
        compose.onNodeWithText("$path · транскрипт не редактируется").assertExists()
    }

    /**
     * Третье состояние статус-строки (решение владельца 2026-08-26 (б)): «Поделиться» из деталки
     * ушло, «Повторить отправку» переехало сюда.
     */
    @Test
    fun `неотправленная запись предлагает повторить`() {
        screen(item = item(local = record(), note = null))
        compose.onNodeWithText("не отправлено · Повторить").performClick()
        assertEquals(1, retried)
        compose.onNodeWithText("Поделиться").assertDoesNotExist()
    }

    /**
     * Критерий приёмки 6: в деталке к причине добавляется начало тела ответа — здесь для него есть
     * место, в строке ленты его нет.
     *
     * «Повторить» остаётся на месте, и тест видит ровно это: клик доходит до `onRetry`. Что тап
     * делает дальше, он не видит и обещать за него не может: запись уходит в очередь заново, только
     * если её цепочка завершена (фатальный код), а на записи в `retry` `KEEP` постановку
     * выбрасывает — долг 1 среза.
     */
    @Test
    fun `причина отказа видна в плашке вместе с телом ответа`() {
        val waiting =
            record()
                .copy(
                    transcribed = false,
                    status = "ошибка ElevenLabs 401\n{\"detail\":\"invalid_api_key\"}",
                )
        screen(item = item(local = waiting, note = null))
        compose
            .onNodeWithText("""ошибка ElevenLabs 401 · {"detail":"invalid_api_key"} · Повторить""")
            .performClick()
        assertEquals(1, retried)
    }

    @Test
    fun `ожидающая правка видна янтарной строкой`() {
        screen(pending = true)
        compose.onNodeWithText("правка в очереди").assertExists()
    }

    @Test
    fun `кнопка GitHub открывает файл заметки`() {
        screen()
        compose.onNodeWithText("Открыть в GitHub").performClick()
        assertEquals(path, opened)
    }

    /** Заметка в `inbox/` ждёт Action: поля у неё не правятся (решение LLD-7). */
    @Test
    fun `у заметки из inbox полей нет`() {
        val raw =
            checkNotNull(
                NoteFile.parse(
                    "inbox/2026-08-24-1807.md",
                    "---\nrecorded: 2026-08-24T18:07:32+03:00\nstatus: raw\n---\n",
                )
            )
        screen(item = FeedItem("20260824-1807", null, raw))
        compose.onNodeWithText("тип").assertDoesNotExist()
        compose.onNodeWithText("участники").assertDoesNotExist()
    }

    /**
     * Файл не нашего формата (без frontmatter) лента показывает — но править его нельзя: `Edit`
     * ставит поля внутрь frontmatter, а его нет, и правка молча ничего не делает. Пока чипы
     * рисовались, владелец жал их вхолостую и без единого сообщения.
     */
    @Test
    fun `у файла без frontmatter полей нет`() {
        val alien = NoteFile.Note("встречи/2026-08-24-1807-chuzhoy.md", emptyMap(), "просто текст")
        screen(item = FeedItem("20260824-1807", null, alien))
        compose.onNodeWithText("тип").assertDoesNotExist()
        compose.onNodeWithText("участники").assertDoesNotExist()
        assertNull(edit)
    }

    /** Пока файла в репо нет, править нечего: чипы не рисуются, а не врут пустыми значениями. */
    @Test
    fun `у записи без файла в репо полей нет`() {
        screen(item = item(local = record(), note = null))
        compose.onNodeWithText("встреча").assertDoesNotExist()
        compose.onNodeWithText("Открыть в GitHub").assertDoesNotExist()
        assertNull(edit)
    }

    /**
     * Критерий приёмки 4: заметка, распознанная ещё Deepgram, открывается как прежде.
     *
     * Что этот тест **видит**: `transcript.md`, собранный `fromDeepgramJson` из живого ответа
     * старого вендора, экран рисует целиком — каждую реплику её текстом и подписью говорящего. Чего
     * он **не видит**: разбор самого `transcript.json`. Файл кладётся рядом только ради
     * достоверности каталога (так выглядит заметка эпохи Deepgram), экран его не читает
     * (`DetailScreen.kt:99-102`) и в приложении не читает никто — «старый JSON разбирается как
     * прежде» держат тесты `fromDeepgramJson`, а не этот.
     */
    @Test
    fun `старая заметка от Deepgram открывается и показывает транскрипт`() {
        val old = record()
        val json =
            requireNotNull(javaClass.classLoader?.getResource("deepgram-sample-response.json")) {
                    "нет образца Deepgram"
                }
                .readText()
        val md = TranscriptMapper.toMarkdown(TranscriptMapper.fromDeepgramJson(json))
        val dir = NotesStore.noteDir(RuntimeEnvironment.getApplication(), old.id)
        File(dir, NotesStore.TRANSCRIPT_JSON).writeText(json)
        File(dir, NotesStore.TRANSCRIPT_MD).writeText(md)

        screen(item = FeedItem("20260824-1807", old, null))
        compose.onNodeWithText("Транскрипт").performClick()
        compose.onNodeWithText("Смотрю, новый").assertExists()
        // «Экран нарисовал все реплики» — это текст каждой из них на экране. Счёт подписей
        // «Спикер 1» мерил бы другое: что все реплики фикстуры от одного говорящего.
        val lines = md.lines().filter(String::isNotBlank)
        lines.forEach { line -> compose.onNodeWithText(line.substringAfter(": ")).assertExists() }
        compose
            .onAllNodesWithText("Спикер 1")
            .assertCountEquals(lines.count { it.contains("] Спикер 1:") })
    }

    private fun record() =
        NotesStore.Note(
            id = "20260824-180732",
            hasAudio = false,
            transcribed = true,
            pushed = false,
            durationSec = 751,
            title = "по релизу",
            preview = "по релизу",
        )
}
