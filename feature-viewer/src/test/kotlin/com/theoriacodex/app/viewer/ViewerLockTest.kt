package com.theoriacodex.app.viewer

import android.app.Application
import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeLeft
import com.theoriacodex.app.viewer.ViewerOwnerTestData.post
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.app.viewer.state.ViewerPanel
import com.theoriacodex.app.viewer.state.ViewerPlaybackMode
import com.theoriacodex.app.viewer.state.ViewerSessionIdentity
import com.theoriacodex.app.viewer.state.ViewerUiState
import com.theoriacodex.app.viewer.state.createViewerUiState
import com.theoriacodex.app.viewer.state.reduceViewerState
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.MediaStream
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ViewerLockTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val identity = ViewerSessionIdentity("watching")
    private val resolvedEpisodes = (1..2).map { index ->
        post(
            "episode-$index",
            SourceKey.HITOMI,
            media = listOf(ImageRef(url = null, localPath = null, mime = "video/mp4", streams = listOf(MediaStream("https://video.test/$index.m3u8", "1080p")))),
            title = "Series · Episode $index",
        )
    }
    private val uiEpisodes = (1..2).map { index ->
        post("episode-$index", SourceKey.HITOMI, emptyList(), title = "Series · Episode $index")
    }

    @Test fun `lock is episodes-only, survives episode advance and same-session replace, and clears on new session or dismiss`() {
        val feedState = createViewerUiState(ViewerSessionIdentity("feed"), listOf(post("p1")))
        assertFalse(reduceViewerState(feedState, ViewerAction.SetLocked(true)).state.controls.locked)

        var state = createViewerUiState(identity, resolvedEpisodes, series = ViewerSeries.Watching(seriesTitle = "Series"))
        state = reduceViewerState(state, ViewerAction.SetPlaybackMode(ViewerPlaybackMode.NEXT)).state
        state = reduceViewerState(state, ViewerAction.ShowPanel(ViewerPanel.EPISODE_STREAM)).state
        state = reduceViewerState(state, ViewerAction.ToggleFillScreen).state
        state = reduceViewerState(state, ViewerAction.SetLocked(true)).state

        assertTrue(state.controls.locked)
        assertTrue(state.controls.fillScreen)
        assertNull(state.controls.panel)
        assertTrue(state.controls.chromeVisible)

        val key0 = requireNotNull(state.currentMedia).key
        state = reduceViewerState(state, ViewerAction.PlaybackCompleted(identity, key0, 0L)).state
        assertEquals(1, state.currentPageIndex)
        assertTrue(state.controls.locked)
        assertTrue(state.controls.fillScreen)

        state = reduceViewerState(
            state,
            ViewerAction.ReplaceSession(identity, resolvedEpisodes, initialPageIndex = 1, series = ViewerSeries.Watching(seriesTitle = "Series")),
        ).state
        assertTrue(state.controls.locked)
        assertTrue(state.controls.fillScreen)

        val dismissed = reduceViewerState(state, ViewerAction.PrepareDismiss).state
        assertFalse(dismissed.controls.locked)

        val fresh = reduceViewerState(
            state,
            ViewerAction.ReplaceSession(ViewerSessionIdentity("other"), resolvedEpisodes, series = ViewerSeries.Watching(seriesTitle = "Series")),
        ).state
        assertFalse(fresh.controls.locked)
        assertFalse(fresh.controls.fillScreen)
    }

    @Test fun `locking hides chrome, blocks gestures, reveals icon-only unlock on tap, and unlocks cleanly`() {
        compose.mainClock.autoAdvance = false
        val state = showWatchingScreen()
        compose.onNodeWithTag(VIEWER_LOCK_TAG).performClick()
        settleFrames()

        assertTrue(state.value.controls.locked)
        compose.onNodeWithTag(VIEWER_EPISODE_CONTROLS_TAG).assertDoesNotExist()
        compose.onNodeWithTag(VIEWER_EPISODE_CENTER_TAG).assertDoesNotExist()
        compose.onNodeWithTag(VIEWER_LOCK_OVERLAY_TAG).assertExists()
        compose.onNodeWithTag(VIEWER_UNLOCK_TAG).assertDoesNotExist()

        compose.onNodeWithTag(VIEWER_LOCK_OVERLAY_TAG).performTouchInput { swipeLeft() }
        compose.onNodeWithTag(VIEWER_LOCK_OVERLAY_TAG).performTouchInput {
            pinch(Offset(width * 0.4f, centerY), Offset(width * 0.2f, centerY), Offset(width * 0.6f, centerY), Offset(width * 0.8f, centerY))
        }
        settleFrames()
        assertEquals(0, state.value.currentPageIndex)
        compose.onNodeWithTag(VIEWER_EPISODE_CONTROLS_TAG).assertDoesNotExist()

        compose.onNodeWithTag(VIEWER_LOCK_OVERLAY_TAG).performClick()
        settleFrames()
        compose.onNodeWithTag(VIEWER_UNLOCK_TAG).assertIsDisplayed()
        compose.onNodeWithText("Unlock").assertDoesNotExist()

        compose.mainClock.advanceTimeBy(VIEWER_UNLOCK_PEEK_MS + 500L)
        settleFrames()
        compose.onNodeWithTag(VIEWER_UNLOCK_TAG).assertDoesNotExist()

        compose.onNodeWithTag(VIEWER_LOCK_OVERLAY_TAG).performClick()
        settleFrames()
        compose.onNodeWithContentDescription("Unlock controls").performClick()
        settleFrames()
        assertFalse(state.value.controls.locked)
        compose.onNodeWithTag(VIEWER_EPISODE_CONTROLS_TAG).assertExists()
    }

    @Test fun `pressing Back while locked reveals Unlock instead of dismissing`() {
        compose.mainClock.autoAdvance = false
        var dismissed = false
        val state = showWatchingScreen(onDismiss = { dismissed = true })
        compose.onNodeWithTag(VIEWER_LOCK_TAG).performClick()
        settleFrames()

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        settleFrames()

        assertFalse(dismissed)
        assertTrue(state.value.controls.locked)
        compose.onNodeWithTag(VIEWER_UNLOCK_TAG).assertIsDisplayed()
    }

    @Test fun `under TalkBack, locking hides chrome, clears media semantics, and keeps Unlock visible`() {
        compose.mainClock.autoAdvance = false
        val am = compose.activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        shadowOf(am).setEnabled(true)
        shadowOf(am).setTouchExplorationEnabled(true)

        val state = showWatchingScreen()
        compose.onNodeWithTag(VIEWER_LOCK_TAG).performClick()
        settleFrames()

        assertTrue(state.value.controls.locked)
        compose.onNodeWithTag(VIEWER_EPISODE_CONTROLS_TAG).assertDoesNotExist()
        compose.onNodeWithContentDescription("Media actions").assertDoesNotExist()
        compose.onNodeWithTag(VIEWER_UNLOCK_TAG).assertIsDisplayed()

        compose.mainClock.advanceTimeBy(VIEWER_UNLOCK_PEEK_MS + 5_000L)
        settleFrames()
        compose.onNodeWithTag(VIEWER_UNLOCK_TAG).assertIsDisplayed()
    }

    private fun showWatchingScreen(onDismiss: () -> Unit = {}): androidx.compose.runtime.MutableState<ViewerUiState> {
        val state = mutableStateOf(
            createViewerUiState(identity, uiEpisodes, requiresResolution = { true }, series = ViewerSeries.Watching(seriesTitle = "Series")),
        )
        compose.setContent {
            MaterialTheme {
                ViewerScreen(
                    uiState = state.value,
                    host = ViewerScreenHost(onAction = { action ->
                        if (action is ViewerAction.PrepareDismiss || action is ViewerAction.Dismiss) onDismiss()
                        state.value = reduceViewerState(state.value, action).state
                    }),
                )
            }
        }
        settleFrames()
        return state
    }

    private fun settleFrames() {
        repeat(VIEWER_CHROME_STAGE_STRIPS + 2) {
            compose.waitForIdle()
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        compose.mainClock.advanceTimeBy(400L)
        compose.waitForIdle()
    }
}
