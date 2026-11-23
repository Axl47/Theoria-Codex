package com.theoriacodex.app.viewer

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.theoriacodex.app.viewer.ViewerOwnerTestData.post
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.app.viewer.state.ViewerEpisodeChoice
import com.theoriacodex.app.viewer.state.ViewerPanel
import com.theoriacodex.app.viewer.state.ViewerSessionIdentity
import com.theoriacodex.app.viewer.state.ViewerSubtitleChoice
import com.theoriacodex.app.viewer.state.ViewerUiState
import com.theoriacodex.app.viewer.state.createViewerUiState
import com.theoriacodex.app.viewer.state.reduceViewerState
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.MediaStream
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ViewerEpisodeControlsTest {
    @get:Rule val compose = createComposeRule()

    private val streams = listOf(MediaStream("https://video.test/1080.m3u8", "1080p · A"), MediaStream("https://video.test/720.m3u8", "720p · B"))
    private val episode = ImageRef(url = null, localPath = null, mime = "video/mp4", streams = streams)
    private val identity = ViewerSessionIdentity("watching")

    @Test fun `the stream pill names the stream playing and switches to the one picked`() {
        val state = episodeState(ViewerEpisodeChoice(streamLabel = "gone"))
        setControls(state)
        // A remembered label the episode no longer offers falls back to its source's first stream.
        compose.onNodeWithTag(VIEWER_EPISODE_STREAM_TAG).assert(hasText("1080p · A")).assert(hasContentDescription("Stream"))
        compose.onNodeWithTag(VIEWER_EPISODE_STREAM_TAG).performClick()
        waitUntil { state.value.controls.panel == ViewerPanel.EPISODE_STREAM }
        compose.onNodeWithTag("$VIEWER_EPISODE_STREAM_TAG:720p · B").performClick()
        waitUntil { state.value.controls.episode.streamLabel == "720p · B" }
        assertNull(state.value.controls.panel)
        compose.onNodeWithTag(VIEWER_EPISODE_STREAM_TAG).assert(hasText("720p · B"))
    }

    @Test fun `subtitles turn off and on, by the label shown`() {
        val state = episodeState()
        val tracks = PlaybackTracks(subtitles = listOf(PlaybackTrack("0:1/0", "English", selected = true), PlaybackTrack("0:2/0", "", selected = false)))
        setControls(state, tracks)
        compose.onNodeWithTag(VIEWER_EPISODE_SUBTITLES_TAG).assert(stateDescription("English"))
        compose.onNodeWithContentDescription("Subtitles").performClick()
        compose.onNodeWithTag("$VIEWER_EPISODE_SUBTITLES_TAG:Off").performClick()
        waitUntil { state.value.controls.episode.subtitle == ViewerSubtitleChoice.Off }
        compose.onNodeWithContentDescription("Subtitles").performClick()
        // A track named by neither label nor language is offered by its place.
        compose.onNodeWithTag("$VIEWER_EPISODE_SUBTITLES_TAG:Track 2").performClick()
        waitUntil { state.value.controls.episode.subtitle == ViewerSubtitleChoice.Track("Track 2") }
    }

    @Test fun `audio is offered only with more than one track`() {
        val state = episodeState()
        val tracks = mutableStateOf(PlaybackTracks(audio = listOf(PlaybackTrack("1:0/0", "Japanese", selected = true))))
        setControls(state) { tracks.value }
        compose.onNodeWithTag(VIEWER_EPISODE_AUDIO_TAG).assertDoesNotExist()
        tracks.value = PlaybackTracks(audio = tracks.value.audio + PlaybackTrack("2:0/0", "English", selected = false))
        compose.onNodeWithContentDescription("Audio").performClick()
        compose.onNodeWithTag("$VIEWER_EPISODE_AUDIO_TAG:English").performClick()
        waitUntil { state.value.controls.episode.audioLabel == "English" }
    }

    @Test fun `a watched series swaps the post strip for episode controls, with no slideshow and no next on the last episode`() {
        val state = watchingScreen()
        compose.onNodeWithTag(VIEWER_EPISODE_CONTROLS_TAG).assertExists()
        compose.onNodeWithTag("viewer-post-navigation-strip").assertDoesNotExist()
        compose.onNodeWithContentDescription("Start slideshow").assertDoesNotExist()
        compose.onNodeWithContentDescription("Next episode").performClick()
        waitUntil { state.value.currentPageIndex == 1 }
        compose.onNodeWithTag("viewer-episode-next").assertDoesNotExist()
    }

    @Test fun `an open episode menu holds the chrome past its auto-hide`() {
        compose.mainClock.autoAdvance = false
        val state = watchingScreen()
        compose.runOnIdle { state.value = reduceViewerState(state.value, ViewerAction.ShowPanel(ViewerPanel.EPISODE_SUBTITLES)).state }
        settleFrames()
        compose.mainClock.advanceTimeBy(6_500)
        settleFrames()
        compose.onNodeWithTag(VIEWER_EPISODE_CONTROLS_TAG).assertExists()
        compose.runOnIdle { assertEquals(ViewerPanel.EPISODE_SUBTITLES, state.value.controls.panel) }

        compose.runOnIdle { state.value = reduceViewerState(state.value, ViewerAction.HidePanel(ViewerPanel.EPISODE_SUBTITLES)).state }
        settleFrames()
        compose.mainClock.advanceTimeBy(6_500)
        settleFrames()
        compose.onNodeWithTag(VIEWER_EPISODE_CONTROLS_TAG).assertDoesNotExist()
    }

    @Test fun `an ordinary post keeps its strip and has no episode controls`() {
        val state = mutableStateOf(createViewerUiState(ViewerSessionIdentity("feed"), listOf(post("first"), post("second"))))
        compose.setContent {
            MaterialTheme {
                ViewerScreen(uiState = state.value, host = ViewerScreenHost(onAction = { state.value = reduceViewerState(state.value, it).state }))
            }
        }
        compose.onNodeWithTag("viewer-post-navigation-strip").assertExists()
        compose.onNodeWithTag(VIEWER_EPISODE_CONTROLS_TAG).assertDoesNotExist()
    }

    /** A Viewer state for one episode, whose controls dispatch to the reducer as the screen's do. */
    private fun episodeState(choice: ViewerEpisodeChoice = ViewerEpisodeChoice()) = mutableStateOf(
        createViewerUiState(identity, listOf(post("episode", media = listOf(episode)))).let { it.copy(controls = it.controls.copy(episode = choice)) },
    )

    private fun setControls(state: androidx.compose.runtime.MutableState<ViewerUiState>, tracks: PlaybackTracks = PlaybackTracks()) =
        setControls(state) { tracks }

    private fun setControls(state: androidx.compose.runtime.MutableState<ViewerUiState>, tracks: () -> PlaybackTracks) {
        val onAction: (ViewerAction) -> Unit = { state.value = reduceViewerState(state.value, it).state }
        compose.setContent {
            MaterialTheme {
                ViewerEpisodeControls(
                    streams = streams, currentStream = episode.viewerStream(state.value.controls.episode.streamLabel), tracks = tracks(),
                    openPanel = state.value.controls.panel,
                    onPanelOpenChange = { panel, open -> onAction(if (open) ViewerAction.ShowPanel(panel) else ViewerAction.HidePanel(panel)) },
                    onAction = onAction, onNext = null,
                )
            }
        }
    }

    /** Two unresolved episodes of a watched series, on the full screen. */
    private fun watchingScreen(): androidx.compose.runtime.MutableState<ViewerUiState> {
        val episodes = (1..2).map { post("episode-$it", SourceKey.HITOMI, emptyList(), title = "Episode $it") }
        val state = mutableStateOf(createViewerUiState(identity, episodes, requiresResolution = { true }, series = ViewerSeries.Watching()))
        compose.setContent {
            MaterialTheme {
                ViewerScreen(
                    uiState = state.value,
                    host = ViewerScreenHost(onAction = { state.value = reduceViewerState(state.value, it).state }),
                )
            }
        }
        settleFrames()
        return state
    }

    private fun settleFrames() = repeat(VIEWER_CHROME_STAGE_STRIPS + 2) {
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun stateDescription(value: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)

    private fun waitUntil(condition: () -> Boolean) = compose.waitUntil(5_000) {
        compose.waitForIdle()
        condition()
    }
}
