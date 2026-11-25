package com.theoriacodex.app.ui

import android.app.Application
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w1000dp-h800dp")
class AdaptiveNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `resizing swaps navigation while retaining content state and scroll`() {
        val width = mutableStateOf(400f)
        val selected = mutableStateOf(0)
        var createdContent = 0
        lateinit var scroll: LazyListState
        compose.setContent {
            MaterialTheme {
                TheoriaNavigationChrome(selected.value, 64.dp, 24.dp, WindowInsets(0),
                    destinations = TopLevelDestination.entries,
                    onDestinationSelected = { selected.value = TopLevelDestination.entries.indexOf(it) },
                    windowWidthDp = width.value,
                ) { modifier ->
                    remember { createdContent++ }
                    scroll = rememberLazyListState()
                    LazyColumn(modifier.testTag("content"), state = scroll) {
                        items(100) { Text("Item $it", Modifier.height(64.dp)) }
                    }
                }
            }
        }
        compose.onNodeWithTag("content").performScrollToIndex(20)
        compose.runOnIdle { width.value = 1000f }
        compose.onNodeWithTag("navigation-rail").assertIsDisplayed()
        val railBounds = compose.onNodeWithTag("navigation-rail").fetchSemanticsNode().boundsInRoot
        val contentBounds = compose.onNodeWithTag("content").fetchSemanticsNode().boundsInRoot
        assertEquals(contentBounds.bottom, railBounds.bottom, 1f)
        compose.onNodeWithText("Library").performClick()
        compose.runOnIdle {
            assertEquals(20, scroll.firstVisibleItemIndex)
            assertEquals(1, createdContent)
            assertEquals(TopLevelDestination.entries.indexOf(TopLevelDestination.Library), selected.value)
            width.value = 400f
        }
        compose.onNodeWithTag("navigation-rail").assertDoesNotExist()
        compose.runOnIdle { assertEquals(20, scroll.firstVisibleItemIndex); assertEquals(1, createdContent) }
    }

    @Test fun `a screen leaving for Home keeps its size while Home already shows its bar (ADR 0222)`() {
        val navigation = openSettingsOverHome(width = 400f)
        val dispatcher = compose.activity.onBackPressedDispatcher
        val leaving = compose.onNodeWithTag("settings").fetchSemanticsNode().size
        compose.runOnIdle { dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 400f, 0f, BackEventCompat.EDGE_LEFT)) }
        compose.runOnIdle { dispatcher.dispatchOnBackProgressed(BackEventCompat(200f, 400f, 0.5f, BackEventCompat.EDGE_LEFT)) }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        compose.onNodeWithTag("home").assertExists()
        compose.onNodeWithContentDescription(TopLevelDestination.Search.label, useUnmergedTree = true).assertExists()
        assertEquals(leaving, compose.onNodeWithTag("settings").fetchSemanticsNode().size)
        compose.runOnIdle { dispatcher.dispatchOnBackCancelled() }
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(leaving, compose.onNodeWithTag("settings").fetchSemanticsNode().size)
        compose.runOnIdle { navigation.popBackStack() }
        repeat(20) {
            compose.mainClock.advanceTimeByFrame()
            compose.onAllNodesWithTag("settings").fetchSemanticsNodes().forEach { assertEquals(leaving, it.size) }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("settings").assertDoesNotExist()
        assertEquals(compose.onNodeWithTag("home-screen").fetchSemanticsNode().size, leaving)
    }

    @Test fun `the rail belongs to Home, so other screens keep the full width (ADR 0222)`() {
        openSettingsOverHome(width = 1000f)
        compose.onNodeWithTag("navigation-rail").assertDoesNotExist()
        assertEquals(
            compose.onNodeWithTag("shell").fetchSemanticsNode().size,
            compose.onNodeWithTag("settings").fetchSemanticsNode().size,
        )
    }

    private fun openSettingsOverHome(width: Float): NavHostController {
        lateinit var navigation: NavHostController
        compose.setContent {
            navigation = rememberNavController()
            MaterialTheme {
                TheoriaAdaptiveScaffold(remember { SnackbarHostState() }) { modifier ->
                    Box(modifier.testTag("shell")) {
                        TheoriaScreenHost(navigation, AppRoute.Home, Modifier.fillMaxSize()) {
                            opaqueScreen(AppRoute.Home, reachesBottomEdge = true) {
                                Box(Modifier.fillMaxSize().testTag("home-screen")) {
                                    TheoriaNavigationChrome(0, 64.dp, 24.dp, WindowInsets(0), TopLevelDestination.entries, {}, windowWidthDp = width) {
                                        Box(it.testTag("home"))
                                    }
                                }
                            }
                            opaqueScreen(AppRoute.Settings) { Box(Modifier.fillMaxSize().testTag("settings")) }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { navigation.navigate(AppRoute.Settings) }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        return navigation
    }
}
