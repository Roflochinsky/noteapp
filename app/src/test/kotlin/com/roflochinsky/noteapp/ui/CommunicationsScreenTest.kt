package com.roflochinsky.noteapp.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.roflochinsky.noteapp.pipeline.Communication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CommunicationsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val incoming = mutableStateOf<Communication?>(null)
    private var saved: Communication? = null
    private var exported: String? = "not called"

    private fun screen(sources: List<Communication> = emptyList()) {
        compose.setContent {
            DocTheme {
                CommunicationsScreen(
                    sources,
                    listOf("A", "B"),
                    incoming.value,
                    null,
                    false,
                    onBack = {},
                    onImport = {},
                    onIncomingConsumed = { incoming.value = null },
                    onSave = { source, done ->
                        saved = source
                        done()
                    },
                    onExport = { project, _ -> exported = project },
                )
            }
        }
    }

    @Test
    fun `accepted draft survives saved state recreation`() {
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        restoration.setContent {
            DocTheme {
                CommunicationsScreen(
                    emptyList(),
                    emptyList(),
                    null,
                    null,
                    false,
                    {},
                    {},
                    {},
                    { value, done ->
                        saved = value
                        done()
                    },
                    { _, _ -> },
                )
            }
        }
        compose.onNodeWithText("Добавить текст").performClick()
        compose.onNodeWithText("Заголовок").performTextInput("Persistent draft")
        compose
            .onNodeWithText("Исходный текст")
            .performScrollTo()
            .performTextInput("x".repeat(90_000))
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Сохранить на устройстве").performScrollTo().performClick()
        assertEquals(90_000, saved?.text?.length)
        assertEquals("Persistent draft", saved?.title)
    }

    @Test
    fun `cancel draft never saves`() {
        screen()
        compose.onNodeWithText("Добавить текст").performClick()
        compose.onNodeWithText("Заголовок").performTextInput("Draft")
        compose.onNodeWithText("Назад").performClick()
        compose.onNodeWithText("Не сохранять").performClick()
        assertNull(saved)
        compose.onNodeWithText("Добавить текст").assertExists()
    }

    @Test
    fun `shared text remains verbatim on explicit save`() {
        incoming.value = Communication(title = "Shared", text = "  original\n")
        screen()
        compose.onNodeWithText("Сохранить на устройстве").performScrollTo().performClick()
        assertEquals("  original\n", saved?.text)
        compose.onNodeWithText("Добавить текст").assertExists()
    }

    @Test
    fun `incoming share does not overwrite current draft without consent`() {
        screen()
        compose.onNodeWithText("Добавить текст").performClick()
        compose.onNodeWithText("Заголовок").performTextInput("Current")
        compose.runOnIdle { incoming.value = Communication(title = "Incoming", text = "other") }
        compose.onNodeWithText("Оставить текущий").performClick()
        compose.onNodeWithText("Current").assertExists()
        compose.onNodeWithText("Incoming").assertDoesNotExist()
    }

    @Test
    fun `project filter controls export and requires confirmation`() {
        screen(
            listOf(
                Communication(title = "A source", project = "A", text = "a"),
                Communication(title = "B source", project = "B", text = "b"),
            )
        )
        compose.onNodeWithText("Проект: Все проекты ▾").performClick()
        compose.onNodeWithText("A").performClick()
        compose.onNodeWithText("B source").assertDoesNotExist()
        compose.onNodeWithText("Сохранить .md").performClick()
        assertEquals("not called", exported)
        compose.onNodeWithText("Продолжить").performClick()
        assertEquals("A", exported)
    }

    @Test
    fun `local workspace is accessible before cloud setup`() {
        var continued = false
        compose.setContent {
            DocTheme {
                OnboardingScreen(listOf(OnboardStep("GitHub", "Optional", false) {})) {
                    continued = true
                }
            }
        }
        compose.onNodeWithText("Продолжить без настройки").performScrollTo().performClick()
        assertTrue(continued)
    }
}
