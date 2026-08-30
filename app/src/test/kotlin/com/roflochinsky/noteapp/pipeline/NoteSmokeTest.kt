package com.roflochinsky.noteapp.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Смоук среза Н5 против **тестового** репо `Roflochinsky/voice-notes-test` (боевой не трогаем):
 * правка поля заметки доезжает до файла в GitHub и видна там.
 *
 * ```
 * NOTEAPP_SMOKE_TOKEN=$(gh auth token) bin/gate testDebugUnitTest --tests '*NoteSmokeTest*'
 * ```
 *
 * Без переменной пропускается — обычный гейт офлайновый.
 *
 * Файл возвращается ровно в исходное состояние: значение поля восстанавливается, а проверяется это
 * не «мы так написали», а blob-SHA из ответа GitHub — он сходится с тем, что был до смоука.
 */
class NoteSmokeTest {

    @get:Rule val tmp = TemporaryFolder()

    private val repo = "Roflochinsky/voice-notes-test"
    private val path = "встречи/2026-08-24-1807-reliz-tgsum.md"

    @Test
    fun `правит поле заметки в живом тестовом репо и возвращает как было`() {
        val token = System.getenv("NOTEAPP_SMOKE_TOKEN").orEmpty()
        assumeTrue("нет NOTEAPP_SMOKE_TOKEN — смоук пропущен", token.isNotEmpty())
        val api = GithubClient(repo, token)
        val store = RepoStore(RepoCache(tmp.newFolder(), repo, token), api)
        assertEquals(SyncStatus.OK, store.refresh())

        val before = api.readFile(path)
        val was = checkNotNull(NoteFile.parse(path, before.text)).project
        println("СМОУК $repo · $path")
        println("  было: project=$was, blob=${before.sha.take(SHA)}")

        val stamp = "смоук-${System.currentTimeMillis() % STAMP}"
        try {
            store.edit(path, Edit.SetField("project", stamp))
            assertEquals(RepoStore.Push.MORE, store.push())
            // Читаем не свой кэш, а GitHub: иначе смоук проверял бы сам себя.
            val written = api.readFile(path)
            println("  стало: project=${NoteFile.parse(path, written.text)?.project}")
            assertEquals(stamp, NoteFile.parse(path, written.text)?.project)
            println("  участники целы: ${NoteFile.parse(path, written.text)?.participants}")
        } finally {
            store.edit(path, Edit.SetField("project", was))
            store.push()
        }

        val after = api.readFile(path)
        println(
            "  вернули: project=${NoteFile.parse(path, after.text)?.project}, blob=${after.sha.take(SHA)}"
        )
        assertEquals("файл вернулся байт в байт", before.text, after.text)
        assertEquals("blob-SHA тот же, что до смоука", before.sha, after.sha)
    }

    private companion object {
        const val STAMP = 100000L
        const val SHA = 7
    }
}
