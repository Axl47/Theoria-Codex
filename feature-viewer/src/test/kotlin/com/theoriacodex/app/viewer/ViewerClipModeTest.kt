package com.theoriacodex.app.viewer

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.theoriacodex.app.testing.waitUntilSettled
import com.theoriacodex.app.viewer.ViewerOwnerTestData.post
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.app.viewer.state.ViewerEffect
import com.theoriacodex.app.viewer.state.ViewerSessionIdentity
import com.theoriacodex.app.viewer.state.createViewerUiState
import com.theoriacodex.app.viewer.state.reduceViewerState
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.MediaStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = ViewerVideoExitTest.PlayerApplication::class)
class ViewerClipModeTest {
    @get:Rule val compose = createComposeRule()

    private val episode = ImageRef(null, null, "video/mp4", streams = listOf(MediaStream("https://video.test/e1.m3u8", "1080p")))
    private val identity = ViewerSessionIdentity("watching")

    @Test fun `clip mode swaps the episode chrome for its own bar and saves the selected range (ADR 0223)`() {
        val effects = mutableListOf<ViewerEffect>()
        val state = mutableStateOf(createViewerUiState(identity, listOf(post("e1", media = listOf(episode))), series = ViewerSeries.Watching()))
        compose.setContent {
            MaterialTheme {
                ViewerScreen(uiState = state.value, host = ViewerScreenHost(onAction = {
                    val reduction = reduceViewerState(state.value, it)
                    state.value = reduction.state
                    effects += reduction.effects
                }))
            }
        }

        compose.onNodeWithContentDescription("More actions").performClick()
        compose.onNodeWithText("Clip").performClick()
        compose.waitUntilSettled(5_000) { state.value.controls.clip != null }
        compose.onNodeWithTag(VIEWER_EPISODE_CONTROLS_TAG).assertDoesNotExist()
        compose.onNodeWithTag(VIEWER_CLIP_SAVE_TAG).assertIsNotEnabled()

        val range = EpisodeClipRange(5_000L, 35_000L)
        compose.runOnIdle { state.value = reduceViewerState(state.value, ViewerAction.SetClipRange(range)).state }
        compose.onNodeWithText("Clip · 0:30").assertExists()
        compose.onNodeWithTag(VIEWER_CLIP_SAVE_TAG).assertIsEnabled().performClick()

        compose.runOnIdle {
            assertNull(state.value.controls.clip)
            assertEquals(listOf(range), effects.filterIsInstance<ViewerEffect.ClipEpisode>().map { it.range })
        }
    }
}
