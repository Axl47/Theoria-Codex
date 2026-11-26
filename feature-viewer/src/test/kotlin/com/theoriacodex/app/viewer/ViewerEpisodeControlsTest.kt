package com.theoriacodex.app.viewer

import android.app.Application
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.media3.common.text.Cue
import com.theoriacodex.app.testing.waitUntilSettled
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test fun `the stream button names the stream playing and switches via the server sheet`() {
        val state = episodeState(ViewerEpisodeChoice(streamLabel = "gone"))
        setControls(state)
        compose.onNodeWithTag(VIEWER_EPISODE_STREAM_TAG).assert(hasText("1080p · A")).assert(hasContentDescription("Stream"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        compose.onNodeWithTag(VIEWER_EPISODE_STREAM_TAG).performClick()
        awaitState { state.value.controls.panel == ViewerPanel.EPISODE_STREAM }
        compose.onNodeWithText("Servers").assertExists()
        compose.onNodeWithTag("$VIEWER_EPISODE_STREAM_TAG:720p · B").performClick()
        awaitState { state.value.controls.episode.streamLabel == "720p · B" }
        assertNull(state.value.controls.panel)
        compose.onNodeWithTag(VIEWER_EPISODE_STREAM_TAG).assert(hasText("720p · B"))
    }

    @Test fun `subtitles turn off and on through the single-choice subtitle sheet`() {
        val state = episodeState()
        val tracks = PlaybackTracks(subtitles = listOf(PlaybackTrack("0:1/0", "English", selected = true), PlaybackTrack("0:2/0", "Signs", selected = false)))
        setControls(state, tracks)
        compose.onNodeWithTag(VIEWER_EPISODE_SUBTITLES_TAG).assert(stateDescription("English"))
        compose.onNodeWithContentDescription("Subtitles").performClick()
        compose.onNodeWithText("Subtitles").assertExists()
        compose.onNodeWithTag("$VIEWER_EPISODE_SUBTITLES_TAG:Off").performClick()
        awaitState { state.value.controls.episode.subtitle == ViewerSubtitleChoice.Off }
        compose.onNodeWithContentDescription("Subtitles").performClick()
        compose.onNodeWithTag("$VIEWER_EPISODE_SUBTITLES_TAG:Signs").performClick()
        awaitState { state.value.controls.episode.subtitle == ViewerSubtitleChoice.Track("Signs") }
    }

    @Test fun `audio is offered only with more than one track`() {
        val state = episodeState()
        val tracks = mutableStateOf(PlaybackTracks(audio = listOf(PlaybackTrack("1:0/0", "Japanese", selected = true))))
        setControls(state) { tracks.value }
        compose.onNodeWithTag(VIEWER_EPISODE_AUDIO_TAG).assertDoesNotExist()
        tracks.value = PlaybackTracks(audio = tracks.value.audio + PlaybackTrack("2:0/0", "English", selected = false))
        compose.onNodeWithContentDescription("Audio").performClick()
        compose.onNodeWithTag("$VIEWER_EPISODE_AUDIO_TAG:English").performClick()
        awaitState { state.value.controls.episode.audioLabel == "English" }
    }

    @Test fun `a watched series omits the bottom dock, shows center prev and next, and disables edge buttons`() {
        val state = watchingScreen(seriesTitle = "Solo Leveling")
        compose.onNodeWithTag(VIEWER_EPISODE_CONTROLS_TAG).assertExists()
        compose.onNodeWithTag("viewer-dock").assertDoesNotExist()
        compose.onNodeWithTag("viewer-post-navigation-strip").assertDoesNotExist()
        compose.onNodeWithContentDescription("Start slideshow").assertDoesNotExist()
        compose.onNodeWithText("Solo Leveling").assertExists()
        compose.onNodeWithText("Episode 1").assertExists()
        compose.onNodeWithTag("viewer-episode-previous").assertIsNotEnabled()
        compose.onNodeWithTag("viewer-episode-next").assertIsEnabled()
        compose.onNodeWithTag(VIEWER_EPISODE_CENTER_PLAY_TAG).assertDoesNotExist()
        compose.onNodeWithTag(VIEWER_EPISODE_SPEED_TAG).assertIsNotEnabled()

        compose.onNodeWithContentDescription("Next episode").performClick()
        awaitState { state.value.currentPageIndex == 1 }
        compose.onNodeWithTag("viewer-episode-previous").assertIsEnabled()
        compose.onNodeWithTag("viewer-episode-next").assertIsNotEnabled()

        compose.onNodeWithContentDescription("Previous episode").performClick()
        awaitState { state.value.currentPageIndex == 0 }
    }

    @Test fun `subtitle overlay stays owned by PlayerView and reconnects across in-place retries and server switches`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
        val playerView = createTexturePlayerView(context)

        val firstState = ViewerVideoState(pending = null)
        val firstOverlay = bindViewerSubtitleOverlay(playerView, firstState, bottomOverlapPx = 24)
        assertEquals(24, firstOverlay.paddingBottom)

        firstState.subtitleView = null
        firstState.setCues(listOf(Cue.Builder().setText("After retry").build()))
        val afterRetry = bindViewerSubtitleOverlay(playerView, firstState, bottomOverlapPx = 48)
        assertTrue(firstOverlay === afterRetry)
        assertEquals(48, afterRetry.paddingBottom)
        assertTrue(firstState.subtitleView === afterRetry)

        val switchedState = ViewerVideoState(pending = null).apply {
            setCues(listOf(Cue.Builder().setText("After switch").build()))
        }
        val afterSwitch = bindViewerSubtitleOverlay(playerView, switchedState, bottomOverlapPx = 0)
        assertTrue(firstOverlay === afterSwitch)
        assertEquals(0, afterSwitch.paddingBottom)
        assertTrue(switchedState.subtitleView === afterSwitch)
    }

    @Test fun `sanitizeViewerSubtitleCue strips embedded colors and backgrounds while preserving italics and placement`() {
        val text = SpannableString("Hello world").apply {
            setSpan(BackgroundColorSpan(android.graphics.Color.BLACK), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(android.graphics.Color.YELLOW), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.ITALIC), 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val raw = Cue.Builder()
            .setText(text)
            .setWindowColor(android.graphics.Color.BLACK)
            .setLine(0.85f, Cue.LINE_TYPE_FRACTION)
            .build()

        val cleaned = sanitizeViewerSubtitleCue(raw)
        assertFalse(cleaned.windowColorSet)
        assertEquals(0.85f, cleaned.line)
        val spanned = cleaned.text as Spanned
        assertTrue(spanned.getSpans(0, spanned.length, BackgroundColorSpan::class.java).isEmpty())
        assertTrue(spanned.getSpans(0, spanned.length, ForegroundColorSpan::class.java).isEmpty())
        val styles = spanned.getSpans(0, spanned.length, StyleSpan::class.java)
        assertEquals(1, styles.size)
        assertEquals(Typeface.ITALIC, styles.single().style)
    }

    @Test fun `topBarTitles splits series and episode cleanly and falls back to one line when episode name is blank`() {
        val series = ViewerSeries.Watching(seriesTitle = "A · B")
        assertEquals("A · B" to "Episode 1", series.topBarTitles(post("e1", title = "A · B · Episode 1")))
        assertEquals("A · B" to null, series.topBarTitles(post("e2", title = "A · B · ")))
        assertEquals("Standalone" to null, ViewerSeries.Watching().topBarTitles(post("e3", title = "Standalone")))
    }

    @Test fun `an open episode sheet holds the chrome past its auto-hide, while an emptied track panel closes itself`() {
        compose.mainClock.autoAdvance = false
        val state = watchingScreen()
        compose.runOnIdle { state.value = reduceViewerState(state.value, ViewerAction.ShowPanel(ViewerPanel.EPISODE_SUBTITLES)).state }
        settleFrames()
        compose.runOnIdle { assertNull(state.value.controls.panel) }

        compose.runOnIdle { state.value = reduceViewerState(state.value, ViewerAction.ShowPanel(ViewerPanel.ACTIONS_MENU)).state }
        settleFrames()
        compose.mainClock.advanceTimeBy(6_500)
        settleFrames()
        compose.onNodeWithTag(VIEWER_EPISODE_CONTROLS_TAG).assertExists()
        compose.runOnIdle { assertEquals(ViewerPanel.ACTIONS_MENU, state.value.controls.panel) }

        compose.runOnIdle { state.value = reduceViewerState(state.value, ViewerAction.HidePanel(ViewerPanel.ACTIONS_MENU)).state }
        settleFrames()
        compose.mainClock.advanceTimeBy(6_500)
        settleFrames()
        compose.onNodeWithTag(VIEWER_EPISODE_CONTROLS_TAG).assertDoesNotExist()
    }

    @Test fun `an ordinary post keeps its dock and strip and has no episode controls`() {
        val state = mutableStateOf(createViewerUiState(ViewerSessionIdentity("feed"), listOf(post("first"), post("second"))))
        compose.setContent {
            MaterialTheme {
                ViewerScreen(uiState = state.value, host = ViewerScreenHost(onAction = { state.value = reduceViewerState(state.value, it).state }))
            }
        }
        compose.onNodeWithTag("viewer-dock").assertExists()
        compose.onNodeWithTag("viewer-post-navigation-strip").assertExists()
        compose.onNodeWithTag(VIEWER_EPISODE_CONTROLS_TAG).assertDoesNotExist()
        compose.onNodeWithTag(VIEWER_EPISODE_CENTER_TAG).assertDoesNotExist()
    }

    private fun episodeState(choice: ViewerEpisodeChoice = ViewerEpisodeChoice()) = mutableStateOf(
        createViewerUiState(identity, listOf(post("episode", media = listOf(episode))), series = ViewerSeries.Watching())
            .let { it.copy(controls = it.controls.copy(episode = choice)) },
    )

    private fun setControls(state: androidx.compose.runtime.MutableState<ViewerUiState>, tracks: PlaybackTracks = PlaybackTracks()) =
        setControls(state) { tracks }

    private fun setControls(state: androidx.compose.runtime.MutableState<ViewerUiState>, tracks: () -> PlaybackTracks) {
        val onAction: (ViewerAction) -> Unit = { state.value = reduceViewerState(state.value, it).state }
        compose.setContent {
            MaterialTheme {
                val currentStream = episode.viewerStream(state.value.controls.episode.streamLabel)
                val openPanel = state.value.controls.panel
                Column {
                    Row {
                        ViewerEpisodeTopActions(
                            currentStream = currentStream,
                            tracks = tracks(),
                            moreExpanded = openPanel == ViewerPanel.ACTIONS_MENU,
                            onOpenPanel = { onAction(ViewerAction.ShowPanel(it)) },
                            onMoreExpandedChange = { onAction(if (it) ViewerAction.ShowPanel(ViewerPanel.ACTIONS_MENU) else ViewerAction.HidePanel(ViewerPanel.ACTIONS_MENU)) },
                            onStartOver = { onAction(ViewerAction.RestartPlayback) },
                            onClip = {},
                        )
                    }
                    ViewerEpisodeControls(
                        playbackRate = closestViewerPlaybackRate(state.value.controls.playback.playbackRate),
                        speedEnabled = state.value.controls.playback.available,
                        rotationOffer = null,
                        showFillToggle = false,
                        fillScreen = state.value.controls.fillScreen,
                        onLock = { onAction(ViewerAction.SetLocked(true)) },
                        onRotate = {},
                        onOpenSpeed = { onAction(ViewerAction.ShowPanel(ViewerPanel.PLAYBACK_SETTINGS)) },
                        onPictureInPicture = null,
                        onToggleFill = { onAction(ViewerAction.ToggleFillScreen) },
                    )
                }
                ViewerEpisodeSheets(
                    streams = streams,
                    currentStream = currentStream,
                    tracks = tracks(),
                    playbackRate = closestViewerPlaybackRate(state.value.controls.playback.playbackRate),
                    openPanel = openPanel,
                    onDismiss = { onAction(ViewerAction.HidePanel(it)) },
                    onAction = onAction,
                )
            }
        }
    }

    private fun watchingScreen(seriesTitle: String? = null): androidx.compose.runtime.MutableState<ViewerUiState> {
        val prefix = seriesTitle?.let { "$it · " }.orEmpty()
        val episodes = (1..2).map { post("episode-$it", SourceKey.HITOMI, emptyList(), title = "${prefix}Episode $it") }
        val state = mutableStateOf(
            createViewerUiState(identity, episodes, requiresResolution = { true }, series = ViewerSeries.Watching(seriesTitle = seriesTitle)),
        )
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

    private fun awaitState(condition: () -> Boolean) = compose.waitUntilSettled(5_000, condition = condition)
}
