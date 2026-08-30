package com.roflochinsky.noteapp.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Смоук записи в реестр против **тестового** репо `Roflochinsky/voice-notes-test` (боевой не
 * трогаем). Токен — из переменной окружения, в файлах его нет:
 * ```
 * NOTEAPP_SMOKE_TOKEN=$(gh auth token) bin/gate --online testDebugUnitTest --tests '*RegistrySmokeTest*'
 * ```
 *
 * Без переменной тест пропускается — обычный гейт остаётся офлайновым. Трогает один файл,
 * `projects.md`, и возвращает его байт в байт обратной правкой в `finally` — даже если проверка
 * посреди прогона упала.
 */
class RegistrySmokeTest {

    @get:Rule val tmp = TemporaryFolder()

    private val repo = "Roflochinsky/voice-notes-test"

    @Test
    fun `заводит проект в живом projects_md и возвращает реестр как был`() {
        val token = System.getenv("NOTEAPP_SMOKE_TOKEN").orEmpty()
        assumeTrue("нет NOTEAPP_SMOKE_TOKEN — смоук пропущен", token.isNotEmpty())
        val api = GithubClient(repo, token)
        val was = api.readFile(Registry.PROJECTS)
        println("СМОУК реестра · было ${was.sha}:\n${was.text}")
        try {
            val store =
                RepoStore(RepoCache(tmp.newFolder(), repo, token), GithubClient(repo, token))
            assertEquals(SyncStatus.OK, store.refresh())

            val name = "смоук-${System.currentTimeMillis() % STAMP}"
            store.addProject(name)
            assertEquals(RepoStore.Push.MORE, store.push())
            assertEquals(RepoStore.Push.EMPTY, store.push())
            val after = api.readFile(Registry.PROJECTS)
            println("СМОУК реестра · стало ${after.sha}:\n${after.text}")
            assertEquals(was.text + "- $name\n", after.text)
            assertTrue(after.text, name in Registry.names(after.text))

            // Тот же проект второй раз: писать нечего — коммита быть не должно, sha не двинулся.
            store.addProject(name)
            assertEquals(RepoStore.Push.MORE, store.push())
            assertEquals(RepoStore.Push.EMPTY, store.push())
            assertEquals("дубликат уехал коммитом", after.sha, api.readFile(Registry.PROJECTS).sha)
        } finally {
            val now = api.readFile(Registry.PROJECTS)
            if (now.sha != was.sha) {
                api.putFile(Registry.PROJECTS, was.text, "смоук: реестр возвращён как был", now.sha)
            }
            val back = api.readFile(Registry.PROJECTS)
            println("СМОУК реестра · вернули ${back.sha}")
            assertEquals("реестр не вернулся в исходное состояние", was.sha, back.sha)
        }
    }

    private companion object {
        const val STAMP = 100_000L
    }
}
