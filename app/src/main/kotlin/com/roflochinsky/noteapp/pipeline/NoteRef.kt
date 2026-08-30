package com.roflochinsky.noteapp.pipeline

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Тождество «локальная запись ↔ файл заметки в репо» (решение LLD-10) и склейка ленты (LLD-11):
 * лента — это записи [NotesStore] ∪ заметки из кэша репо, а не два разных списка.
 *
 * Ref — минута записи, `ггггММдд-ЧЧмм`. Точнее тождество взять неоткуда: телефон кладёт файл под
 * именем `ГГГГ-ММ-ДД-ЧЧММ.md` (спека формата, решение 2), то есть секунд в репо нет ни у кого.
 *
 * **Ref — ключ СКЛЕЙКИ, а не уникальный ключ строки.** Две записи в одну минуту делят ref законно,
 * и это не значит, что они одна заметка: Action переносит их под разными слагами в разные папки
 * типов, файлы у них разные. Поэтому [merge] разводит совпавшие по ref пары по одной (запись —
 * своему файлу), а лишние остаются отдельными строками: «ошибка не теряет запись». Уникальный ключ
 * строки — для списка и навигации — даёт [FeedItem.key].
 *
 * Тождество переживает переименование Action-ом (`renamed`/`previous_filename` в `compare`) само
 * собой: `recorded` в frontmatter при переносе не меняется, а имя файла — запасной путь на случай
 * заметки без frontmatter. Отдельного индекса «путь → запись» поэтому нет и хранить его негде:
 * `findDonePath`, искавший файл по префиксу имени в дереве репо, здесь и заканчивается.
 */
object NoteRef {

    /** Папки заметок: пять типов ADR плюс `inbox/`; вложенности в них нет (решение LLD-22). */
    private const val INBOX = "inbox/"

    private val DIRS = setOf("inbox", "встречи", "идеи", "задачи", "личное", "другое")

    private val ID = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    private val REF = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")

    /** `2026-08-24-1807` в начале имени файла — запасное тождество, когда frontmatter нет. */
    private val NAME = Regex("""^(\d{4})-(\d{2})-(\d{2})-(\d{2})(\d{2})""")

    /** `ггггММдд` — дата ref до дефиса. */
    private const val DATE_LEN = 8

    fun isNote(path: String): Boolean =
        path.endsWith(".md") && path.substringBefore('/') in DIRS && path.count { it == '/' } == 1

    /**
     * `inbox/` для приложения — только чтение (решение LLD-7): пока файл лежит там, его
     * обрабатывает Action (`status: raw`), и правка полей с телефона гонялась бы с ним за один и
     * тот же файл. Как только Action перенёс заметку в папку типа — поля правятся.
     */
    fun isEditable(path: String): Boolean = isNote(path) && !path.startsWith(INBOX)

    /** Ref локальной записи: её id — та же минута плюс секунды. */
    fun of(noteId: String): String =
        runCatching { LocalDateTime.parse(noteId, ID).format(REF) }.getOrDefault(noteId)

    /**
     * Ref заметки из репо: **`recorded` бьёт имя файла**, поэтому он первый. Асимметрия записана в
     * промпте Action (`docs/examples/process-notes.yml`): «поле `recorded` не трогай», а имя файла
     * он как раз переписывает — переносит в папку типа и дописывает слаг. Значит на расхождении
     * прав frontmatter от телефона, а не имя, придуманное моделью.
     *
     * Не разобралось ни то ни другое — ref сам путь: строка ленты останется одинокой, но заметка не
     * пропадёт.
     */
    fun of(note: NoteFile.Note): String =
        recorded(note.fields["recorded"]) ?: named(note.path) ?: note.path

    private fun recorded(value: String?): String? =
        value?.let { runCatching { OffsetDateTime.parse(it.trim()).format(REF) }.getOrNull() }

    private fun named(path: String): String? =
        NAME.find(path.substringAfterLast('/'))?.let { m ->
            m.groupValues.drop(1).joinToString("").let {
                it.take(DATE_LEN) + "-" + it.drop(DATE_LEN)
            }
        }

    /**
     * Лента одним списком: та же заметка с телефона и из репо — одна строка. Свежие сверху; строки
     * без разобранного времени (чужой файл в папке типа) уходят вниз, но не теряются.
     *
     * Совпавшие по ref разбираются **парами, по одной**, а не картой «ref → заметка»: карта теряла
     * все файлы одной минуты, кроме последнего (блокер ревью Н5), а одна и та же заметка,
     * подставленная двум записям, показала бы её дважды. Не нашедшая пары сторона — своя строка.
     *
     * **Отправленные записи разбирают файлы первыми.** Список записей идёт от свежего, а свежая
     * запись минуты — как раз та, что ещё лежит в очереди: она забирала файл, приехавший от ранней
     * записи той же минуты, и владелец видел на своей неотправленной записи чужой заголовок, чужие
     * поля и «✓ в GitHub» (П2 ревью Н5). Файла у неотправленной записи в репо быть не может — это и
     * есть сигнал.
     *
     * `pushed` задаёт ПОРЯДОК разбора, а не запрет: флажок `pushed.txt` теряется при переустановке
     * приложения, и запрет оставил бы файл сиротой — третьей строкой, двойником одной из записей.
     * Сортировка устойчивая, поэтому вне спорной минуты порядок ленты не меняется.
     */
    fun merge(local: List<NotesStore.Note>, notes: List<NoteFile.Note>): List<FeedItem> {
        val byRef = notes.groupBy(::of).mapValues { (_, same) -> same.toMutableList() }
        val fromRecords =
            local
                .sortedByDescending { it.pushed }
                .map { record ->
                    val ref = of(record.id)
                    FeedItem(ref, record, byRef[ref]?.removeFirstOrNull())
                }
        val fromRepo = byRef.values.flatten().map { FeedItem(of(it), null, it) }
        return (fromRecords + fromRepo).sortedWith(
            compareByDescending<FeedItem> { it.time != null }.thenByDescending { it.ref }
        )
    }
}

/**
 * Строка ленты: локальная запись, её файл в репо или и то и другое. Пустых полей не заводим —
 * значение спрашивается у той стороны, которая его знает: заголовок и поля даёт заметка из репо (их
 * придумал Claude), аудио, длительность и статус доставки — телефон.
 */
data class FeedItem(val ref: String, val local: NotesStore.Note?, val note: NoteFile.Note?) {

    /**
     * Уникальный ключ строки — списка [androidx.compose.foundation.lazy.LazyColumn] и навигации.
     * Это НЕ [ref]: ref точен до минуты, потому что он ключ склейки, и две записи одной минуты
     * делят его законно. Уникальность даёт источник строки: id записи точен до секунды, путь файла
     * уникален в репо. Ключ у строки не меняется, пока не сменился её источник.
     *
     * У строки БЕЗ локальной записи ключ и есть путь, поэтому перенос файла Action-ом такую
     * открытую деталку закрывает (до Н5 ключом был ref, и он это переживал). Цена принята: пока
     * Action переносит заметку, запись с телефона у неё почти всегда есть, а без записи владелец
     * попадает на ленту, а не в ошибку. Возврат по ref подобрал бы соседа по минуте — хуже.
     *
     * Хвост `?: ref` до сих пор недостижим — [NoteRef.merge] строк с двумя пустыми сторонами не
     * строит. Оставлен потому, что [FeedItem] — открытый data class: конструктор такую строку
     * разрешает, и ключ у неё должен быть хоть какой-то, а не исключение.
     */
    val key: String
        get() = local?.id ?: note?.path ?: ref

    val noteId: String?
        get() = local?.id

    val path: String?
        get() = note?.path

    val time: LocalDateTime?
        get() = runCatching { LocalDateTime.parse(ref, REF) }.getOrNull()

    /** Заголовок Claude, пока его нет — первая фраза транскрипта; пусто — рисует экран. */
    val title: String
        get() = note?.title ?: local?.title.orEmpty()

    /** Превью — «Суть» саммари, а до саммари первые строки транскрипта с телефона. */
    val preview: String
        get() = note?.let(::lead)?.takeIf { it.isNotEmpty() } ?: local?.preview.orEmpty()

    val type: String?
        get() = note?.type

    val project: String?
        get() = note?.project

    val participants: List<String>
        get() = note?.participants.orEmpty()

    val tags: List<String>
        get() = note?.tags.orEmpty()

    /** Расшифровка есть: на телефоне — файлом, в репо — самим фактом заметки. */
    val transcribed: Boolean
        get() = local?.transcribed ?: true

    /** Файл в кэше репо — доставка уже доказана, флажок `pushed.txt` тут не нужен. */
    val pushed: Boolean
        get() = note != null || local?.pushed == true

    val durationSec: Long
        get() = local?.durationSec?.takeIf { it > 0 } ?: seconds(note?.fields?.get("duration"))

    /** Первый абзац «Сути»: то же, что комп показывает превью строки ленты. */
    private fun lead(note: NoteFile.Note): String =
        note
            .section(NoteFile.SUMMARY)
            .orEmpty()
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("**") }
            ?.substringAfter("**")
            ?.substringAfter("**")
            ?.trim()
            .orEmpty()

    private fun seconds(duration: String?): Long =
        duration
            .orEmpty()
            .split(':')
            .mapNotNull { it.trim().toLongOrNull() }
            .takeIf { it.isNotEmpty() }
            ?.reduce { acc, part -> acc * SEC_IN_MIN + part } ?: 0

    private companion object {
        val REF: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
        const val SEC_IN_MIN = 60L
    }
}
