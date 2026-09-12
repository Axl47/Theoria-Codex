package com.theoriacodex.app.ui

import android.app.Application
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w1000dp-h800dp")
class AdaptiveNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `resizing swaps navigation while retaining content state and scroll`() {
        val width = mutableStateOf(400f)
        val selected = mutableStateOf(0)
        var createdContent = 0
        var firstVisible = 0
        compose.setContent {
            MaterialTheme {
                TheoriaAdaptiveScaffold(true, selected.value, 64.dp, 24.dp, WindowInsets(0),
                    remember { SnackbarHostState() },
                    onDestinationSelected = { selected.value = TopLevelDestination.entries.indexOf(it) },
                    windowWidthDp = width.value,
                ) { modifier ->
                    remember { createdContent++ }
                    val scroll = rememberLazyListState()
                    firstVisible = scroll.firstVisibleItemIndex
                    LazyColumn(modifier.testTag("content"), state = scroll) {
                        items(100) { Text("Item $it", Modifier.height(64.dp)) }
                    }
                }
            }
        }
        compose.onNodeWithTag("content").performScrollToIndex(20)
        compose.runOnIdle { width.value = 1000f }
        compose.onNodeWithTag("navigation-rail").assertIsDisplayed()
        compose.onNodeWithText("Settings").performClick()
        compose.runOnIdle {
            assertEquals(20, firstVisible)
            assertEquals(1, createdContent)
            assertEquals(TopLevelDestination.entries.indexOf(TopLevelDestination.Settings), selected.value)
            width.value = 400f
        }
        compose.onNodeWithTag("navigation-rail").assertDoesNotExist()
        compose.runOnIdle { assertEquals(20, firstVisible); assertEquals(1, createdContent) }
    }
}
