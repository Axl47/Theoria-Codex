package com.theoriacodex.app.viewer

import android.app.Application
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.theoriacodex.app.viewer.state.VIEWER_SLIDESHOW_IMAGE_MS
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.app.viewer.state.ViewerControlsState
import com.theoriacodex.app.viewer.state.ViewerDwell
import com.theoriacodex.app.viewer.state.ViewerPlaybackMode
import com.theoriacodex.app.viewer.state.ViewerUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ViewerFrameTest {
    @get:Rule val compose = createComposeRule()

    private val seen = mutableListOf<Boolean>()

    @Composable
    private fun ChromeReader(frame: ViewerFrame) {
        val visible = frame.chromeVisible
        SideEffect { if (seen.lastOrNull() != visible) seen += visible }
    }

    @Test fun `a chrome toggle keeps the frame and controls while their readers follow it (ADR 0166)`() {
        val post = ViewerOwnerTestData.post("a")
        val navigation = viewerPostNavigation(ViewerUiState())
        val selection = ViewerSelection(post, post.media, 0, false, emptyList())
        val screen = ViewerScreenState()
        val onAction: (ViewerAction) -> Unit = {}
        val uiState = mutableStateOf(ViewerUiState())
        val frames = mutableListOf<ViewerFrame>()
        val controls = mutableListOf<ViewerControls>()
        compose.setContent {
            val chromeVisible = rememberUpdatedState(uiState.value.controls.chromeVisible)
            val stage = remember { mutableIntStateOf(0) }
            val frame = rememberViewerFrame(uiState.value, navigation, listOf(post), selection, chromeVisible, stage, false, false, false, false, false)
            val control = rememberViewerControls(onAction, screen, false, chromeVisible, false, false)
            SideEffect {
                if (frames.lastOrNull() !== frame) frames += frame
                if (controls.lastOrNull() !== control) controls += control
            }
            ChromeReader(frame)
        }

        fun update(change: (ViewerUiState) -> ViewerUiState) {
            compose.runOnIdle { uiState.value = change(uiState.value) }
            compose.waitForIdle()
        }

        update { it.copy(controls = it.controls.copy(chromeVisible = false)) }
        update { it.copy(controls = it.controls.copy(chromeVisible = true)) }
        compose.runOnIdle {
            assertEquals("what a skipping reader saw", listOf(true, false, true), seen)
            assertEquals("frames", 1, frames.size)
            assertEquals("controls", 1, controls.size)
        }
        update { it.copy(controls = it.controls.copy(locked = true)) }
        compose.runOnIdle { assertEquals("any other change builds a new frame", 2, frames.size) }
    }

    @Test fun `a held or still-dwelling Play Next video repeats in the player until the dwell has passed (ADR 0217)`() {
        val post = ViewerOwnerTestData.post("a")
        val selection = ViewerSelection(post, post.media, 0, false, emptyList())
        val uiState = mutableStateOf(ViewerUiState())
        val frames = mutableListOf<ViewerFrame>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val chromeVisible = rememberUpdatedState(true)
            val stage = remember { mutableIntStateOf(0) }
            val navigation = remember { viewerPostNavigation(ViewerUiState()) }
            val frame = rememberViewerFrame(uiState.value, navigation, listOf(post), selection, chromeVisible, stage, false, false, false, false, false)
            SideEffect { if (frames.lastOrNull() !== frame) frames += frame }
        }
        fun repeatsAfter(waitMs: Long, controls: (ViewerControlsState) -> ViewerControlsState): List<Boolean> {
            compose.runOnUiThread {
                uiState.value = ViewerUiState().let { it.copy(controls = controls(it.controls)) }
                Snapshot.sendApplyNotifications()
            }
            compose.mainClock.advanceTimeByFrame()
            val frame = frames.last()
            val before = frame.videoRepeats
            compose.mainClock.advanceTimeBy(waitMs)
            compose.mainClock.advanceTimeByFrame()
            assertSame("the dwell passing keeps the frame", frame, frames.last())
            return listOf(before, frame.videoRepeats)
        }
        fun dwelling(held: Boolean = false, interacting: Boolean = false): (ViewerControlsState) -> ViewerControlsState = {
            it.copy(playbackMode = ViewerPlaybackMode.NEXT, dwell = ViewerDwell(SystemClock.uptimeMillis(), held, interacting))
        }

        assertEquals("dwelling", listOf(true, false), repeatsAfter(VIEWER_SLIDESHOW_IMAGE_MS, dwelling()))
        assertEquals("held", listOf(true, true), repeatsAfter(VIEWER_SLIDESHOW_IMAGE_MS, dwelling(held = true)))
        assertEquals("touched", listOf(true, true), repeatsAfter(VIEWER_SLIDESHOW_IMAGE_MS, dwelling(interacting = true)))
        assertEquals("loop", listOf(true, true), repeatsAfter(0L) { it.copy(playbackMode = ViewerPlaybackMode.LOOP) })
        assertEquals("once", listOf(false, false), repeatsAfter(0L) { it.copy(playbackMode = ViewerPlaybackMode.ONCE) })
    }
}
