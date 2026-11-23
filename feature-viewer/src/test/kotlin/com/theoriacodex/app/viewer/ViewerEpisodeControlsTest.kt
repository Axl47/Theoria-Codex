package com.theoriacodex.app.viewer

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.theoriacodex.app.viewer.ViewerOwnerTestData.post
import com.theoriacodex.app.viewer.state.ViewerSessionIdentity
import com.theoriacodex.app.viewer.state.createViewerUiState
import com.theoriacodex.app.viewer.state.reduceViewerState
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.MediaStream
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
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

    @Test fun `the stream pill names the stream playing and switches to the one picked`() {
        val label = mutableStateOf<String?>("gone")
        setControls(onStreamSelected = { label.value = it.label }, current = { episode.viewerStream(label.value) })
        // A remembered label the episode no longer offers falls back to its source's first stream.
        compose.onNodeWithTag("viewer-episode-stream").assert(hasText("1080p · A"))
        compose.onNodeWithTag("viewer-episode-stream").performClick()
        compose.onNodeWithText("720p · B").performClick()
        waitUntil { label.value == "720p · B" }
        compose.onNodeWithTag("viewer-episode-stream").assert(hasText("720p · B"))
    }

    @Test fun `subtitles turn off and on through the player's tracks`() {
        val picked = mutableListOf<String?>()
        val tracks = PlaybackTracks(subtitles = listOf(PlaybackTrack("text:0", "English", selected = true), PlaybackTrack("text:1", "", selected = false)))
        setControls(tracks = tracks, onSubtitleSelected = { picked += it })
        compose.onNodeWithTag("viewer-episode-subtitles").assert(stateDescription("English"))
        compose.onNodeWithContentDescription("Subtitles").performClick()
        compose.onNodeWithText("Off").performClick()
        compose.onNodeWithContentDescription("Subtitles").performClick()
        // A track named by neither label nor language is offered by its place.
        compose.onNodeWithText("Track 2").performClick()
        waitUntil { picked == listOf(null, "text:1") }
    }

    @Test fun `audio is offered only with more than one track`() {
        val tracks = mutableStateOf(PlaybackTracks(audio = listOf(PlaybackTrack("audio:0", "Japanese", selected = true))))
        val picked = mutableListOf<String>()
        setControls(tracks = { tracks.value }, onAudioSelected = { picked += it })
        compose.onNodeWithTag("viewer-episode-audio").assertDoesNotExist()
        tracks.value = PlaybackTracks(audio = tracks.value.audio + PlaybackTrack("audio:1", "English", selected = false))
        compose.onNodeWithContentDescription("Audio").performClick()
        compose.onNodeWithText("English").performClick()
        waitUntil { picked == listOf("audio:1") }
    }

    @Test fun `a watched series swaps the post strip for episode controls, with no next on the last episode`() {
        val identity = ViewerSessionIdentity("watching")
        val episodes = (1..2).map { post("episode-$it", SourceKey.HITOMI, emptyList(), title = "Episode $it") }
        val state = mutableStateOf(createViewerUiState(identity, episodes, requiresResolution = { true }))
        compose.setContent {
            MaterialTheme {
                ViewerScreen(
                    uiState = state.value,
                    host = ViewerScreenHost(onAction = { state.value = reduceViewerState(state.value, it).state }),
                    config = ViewerScreenConfig(series = ViewerSeries.Watching()),
                )
            }
        }
        compose.onNodeWithTag(VIEWER_EPISODE_CONTROLS_TAG).assertExists()
        compose.onNodeWithTag("viewer-post-navigation-strip").assertDoesNotExist()
        compose.onNodeWithContentDescription("Next episode").performClick()
        waitUntil { state.value.currentPageIndex == 1 }
        compose.onNodeWithTag("viewer-episode-next").assertDoesNotExist()
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

    private fun setControls(
        tracks: PlaybackTracks = PlaybackTracks(),
        onSubtitleSelected: (String?) -> Unit = {},
        onStreamSelected: (MediaStream) -> Unit = {},
        current: () -> MediaStream? = { streams.first() },
    ) = setControls({ tracks }, onSubtitleSelected = onSubtitleSelected, onStreamSelected = onStreamSelected, current = current)

    private fun setControls(
        tracks: () -> PlaybackTracks,
        onSubtitleSelected: (String?) -> Unit = {},
        onAudioSelected: (String) -> Unit = {},
        onStreamSelected: (MediaStream) -> Unit = {},
        current: () -> MediaStream? = { streams.first() },
    ) {
        compose.setContent {
            MaterialTheme {
                ViewerEpisodeControls(
                    streams = streams, currentStream = current(), onStreamSelected = onStreamSelected, tracks = tracks(),
                    onSubtitleSelected = onSubtitleSelected, onAudioSelected = onAudioSelected, onNext = null, onInteraction = {},
                )
            }
        }
    }

    private fun stateDescription(value: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)

    private fun waitUntil(condition: () -> Boolean) = compose.waitUntil(5_000) {
        compose.waitForIdle()
        condition()
    }
}
