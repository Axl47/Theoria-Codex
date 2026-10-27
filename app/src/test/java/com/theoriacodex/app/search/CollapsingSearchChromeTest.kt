package com.theoriacodex.app.search

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CollapsingSearchChromeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `collapsed controls float over a full-height grid and expand on tap`() {
        val expanded = mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                CollapsingSearchChrome(expanded.value, false, { expanded.value = true },
                    sourcePicker = { Text("Source") }, inputField = { Text("Search input") }, modifier = Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).testTag("grid"))
                }
            }
        }
        compose.onNodeWithText("Search input").assertDoesNotExist()
        assertEquals(0f, compose.onNodeWithTag("grid").fetchSemanticsNode().positionInRoot.y)
        compose.onNodeWithContentDescription("Expand Search").performClick()
        compose.onNodeWithText("Search input").assertExists()
        compose.waitForIdle()
        assertTrue(compose.onNodeWithTag("grid").fetchSemanticsNode().positionInRoot.y > 0f)
        compose.runOnIdle { expanded.value = false }
        compose.waitForIdle()
        compose.onNodeWithText("Search input").assertDoesNotExist()
        assertEquals(0f, compose.onNodeWithTag("grid").fetchSemanticsNode().positionInRoot.y)
    }

    @Test fun `legacy mode keeps the full input above the grid`() {
        compose.setContent {
            MaterialTheme {
                CollapsingSearchChrome(false, true, {}, sourcePicker = {}, inputField = { Text("Search input") }, modifier = Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).testTag("grid"))
                }
            }
        }
        compose.onNodeWithText("Search input").assertExists()
        compose.onNodeWithContentDescription("Expand Search").assertDoesNotExist()
        assertTrue(compose.onNodeWithTag("grid").fetchSemanticsNode().positionInRoot.y > 0f)
    }
}
