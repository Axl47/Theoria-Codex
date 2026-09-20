package com.theoriacodex.app.viewer

import android.app.Application
import android.view.accessibility.AccessibilityManager as AndroidAccessibilityManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.AccessibilityManager
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.core.app.ApplicationProvider
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.app.viewer.state.ViewerSessionIdentity
import com.theoriacodex.app.viewer.state.createViewerUiState
import com.theoriacodex.app.viewer.state.reduceViewerState
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ViewerAccessibilityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `Viewer keeps controls reachable when touch exploration is enabled during viewing`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context.getSystemService(AndroidAccessibilityManager::class.java)).setTouchExplorationEnabled(false)
        lateinit var viewerManager: AndroidAccessibilityManager
        val initial = createViewerUiState(ViewerSessionIdentity("accessibility", "query", "RECENTS"), listOf(post()))
        val state = mutableStateOf(initial)
        val actions = mutableListOf<ViewerAction>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val localManager = requireNotNull(LocalContext.current.getSystemService(AndroidAccessibilityManager::class.java))
            SideEffect { viewerManager = localManager }
            MaterialTheme {
                ViewerScreen(
                    uiState = state.value, creatorBrowsingSources = emptySet(),
                    onAction = { action -> actions += action; state.value = reduceViewerState(state.value, action).state },
                    onOpenInBrowser = {}, onRemoveIncludeTerm = { _, _ -> },
                    onRemoveExcludeTerm = { _, _ -> }, onGoToSearch = {},
                )
            }
        }
        compose.mainClock.advanceTimeBy(6_000)
        compose.onNodeWithContentDescription("Back").assertDoesNotExist()
        // Robolectric delivers service events to the listeners on this exact context's manager.
        compose.runOnIdle { actions.clear(); shadowOf(viewerManager).setTouchExplorationEnabled(true) }
        settleFrame()
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(20_000)
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        compose.runOnIdle { assertFalse(ViewerAction.ToggleChrome in actions) }
        compose.runOnIdle { shadowOf(viewerManager).setTouchExplorationEnabled(false) }
        settleFrame()
        compose.onNodeWithContentDescription("Back").assertDoesNotExist()
    }

    @Test fun `auto hide honors recommended control timeout and resets after interaction`() {
        val serial = mutableStateOf(0)
        val blocked = mutableStateOf(false)
        var hidden = 0
        val requested = mutableListOf<List<Boolean>>()
        val manager = object : AccessibilityManager {
            override fun calculateRecommendedTimeoutMillis(
                originalTimeoutMillis: Long, containsIcons: Boolean, containsText: Boolean, containsControls: Boolean,
            ): Long {
                assertEquals(5_000L, originalTimeoutMillis)
                requested += listOf(containsIcons, containsText, containsControls)
                return 12_000L
            }
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalAccessibilityManager provides manager) {
                ViewerChromeAutoHideEffect(true, serial.value, blocked.value, false) { hidden++ }
                Text("Viewer")
            }
        }
        compose.mainClock.advanceTimeBy(6_000)
        compose.runOnIdle { assertEquals(0, hidden); serial.value++ }
        settleFrame()
        compose.mainClock.advanceTimeBy(11_000)
        compose.runOnIdle { assertEquals(0, hidden) }
        compose.mainClock.advanceTimeBy(2_000)
        compose.runOnIdle {
            assertEquals(1, hidden)
            assertTrue(requested.all { it == listOf(true, true, true) })
            blocked.value = true
            serial.value++
        }
        settleFrame()
        compose.mainClock.advanceTimeBy(20_000)
        compose.runOnIdle { assertEquals(1, hidden); blocked.value = false }
        settleFrame()
        compose.mainClock.advanceTimeBy(13_000)
        compose.runOnIdle { assertEquals(2, hidden) }
    }

    private fun settleFrame() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun post() = Post(
        id = PostId(SourceKey.GELBOORU, "accessibility"),
        preview = ImageRef(url = null, localPath = null, mime = "image/jpeg"),
        full = null, pageUrl = null, width = 800, height = 1_000,
        canonicalTags = emptyList(), rawTags = emptyList(), authorName = null, createdAtEpochMs = null,
    )
}
