package com.theoriacodex.app.search

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.theoriacodex.app.ui.components.CollapsingSearchChrome
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

    @Test fun `collapsed corner strip reaches the screen edge over a full-height grid and expands on tap`() {
        val expanded = mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                CollapsingSearchChrome(expanded.value, false, { expanded.value = true },
                    trailingControl = { Text("Source") }, inputField = { Text("Search input") },
                    modifier = Modifier.fillMaxSize().testTag("chrome"), horizontalPadding = 16.dp) {
                    Box(Modifier.weight(1f).testTag("grid"))
                }
            }
        }
        compose.onNodeWithText("Search input").assertDoesNotExist()
        assertEquals(compose.onNodeWithTag("chrome").fetchSemanticsNode().boundsInRoot.right,
            compose.onNodeWithTag("collapsed-search-strip").fetchSemanticsNode().boundsInRoot.right)
        assertEquals(0f, compose.onNodeWithTag("grid").fetchSemanticsNode().positionInRoot.y)
        compose.onNodeWithContentDescription("Expand Search").performClick()
        compose.onNodeWithText("Search input").assertExists()
        compose.onNodeWithTag("collapsed-search-strip").assertDoesNotExist()
        compose.waitForIdle()
        assertTrue(compose.onNodeWithTag("grid").fetchSemanticsNode().positionInRoot.y > 0f)
        compose.runOnIdle { expanded.value = false }
        compose.waitForIdle()
        compose.onNodeWithText("Search input").assertDoesNotExist()
        assertEquals(0f, compose.onNodeWithTag("grid").fetchSemanticsNode().positionInRoot.y)
    }

    @Test fun `an expanded bar reserves its space on the first frame after returning to Search`() {
        val shown = mutableStateOf(true)
        compose.setContent {
            val holder = rememberSaveableStateHolder()
            MaterialTheme {
                if (shown.value) holder.SaveableStateProvider("search") {
                    CollapsingSearchChrome(true, false, {}, trailingControl = {},
                        inputField = { Box(Modifier.fillMaxWidth().height(56.dp)) }, modifier = Modifier.fillMaxSize()) {
                        remember { compositions++ }
                        Box(Modifier.weight(1f).testTag("grid"))
                    }
                }
            }
        }
        compose.waitForIdle()
        val settledTop = gridTop()
        compose.runOnIdle { shown.value = false }
        compose.waitForIdle()

        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { shown.value = true }
        repeat(MAX_RETURN_FRAMES) {
            if (compose.onAllNodesWithTag("grid").fetchSemanticsNodes().isEmpty()) compose.mainClock.advanceTimeByFrame()
        }

        assertEquals(2, compositions)
        assertEquals(settledTop, gridTop())
    }

    @Test fun `legacy mode keeps the full input above the grid`() {
        compose.setContent {
            MaterialTheme {
                CollapsingSearchChrome(false, true, {}, trailingControl = {}, inputField = { Text("Search input") }, modifier = Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).testTag("grid"))
                }
            }
        }
        compose.onNodeWithText("Search input").assertExists()
        compose.onNodeWithContentDescription("Expand Search").assertDoesNotExist()
        assertTrue(compose.onNodeWithTag("grid").fetchSemanticsNode().positionInRoot.y > 0f)
    }

    private var compositions = 0

    private fun gridTop() = compose.onNodeWithTag("grid").fetchSemanticsNode().positionInRoot.y

    private companion object {
        const val MAX_RETURN_FRAMES = 3
    }
}
