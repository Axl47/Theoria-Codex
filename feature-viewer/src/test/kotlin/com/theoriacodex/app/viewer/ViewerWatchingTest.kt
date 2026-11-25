package com.theoriacodex.app.viewer

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.SavedStateHandle
import com.theoriacodex.app.viewer.ViewerOwnerTestData.post
import com.theoriacodex.app.viewer.ViewerOwnerTestData.session
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.app.viewer.state.ViewerEpisodeChoice
import com.theoriacodex.app.viewer.state.ViewerMediaError
import com.theoriacodex.app.viewer.state.ViewerPanel
import com.theoriacodex.app.viewer.state.ViewerPlaybackMode
import com.theoriacodex.app.viewer.state.ViewerResolutionStatus
import com.theoriacodex.app.viewer.state.ViewerSessionIdentity
import com.theoriacodex.app.viewer.state.ViewerSubtitleChoice
import com.theoriacodex.app.viewer.state.createViewerUiState
import com.theoriacodex.app.viewer.state.reduceViewerState
import com.theoriacodex.domain.model.GalleryReadingMode
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class ViewerWatchingTest {
    private val episodes = (1..3).map { post("episode-$it", SourceKey.HITOMI, emptyList()) }

    @Test
    fun `a watched episode resolves alone, without looking its neighbours up ahead`() = runTest {
        val started = mutableListOf<PostId>()
        val owner = testViewerOwner(SavedStateHandle(), postResolver = ViewerPostResolver { _, id ->
            started += id
            null
        }, scopeOverride = this)
        owner.replaceSession(session("watching", episodes, startIndex = 1).copy(series = ViewerSeries.Watching()))
        advanceUntilIdle()
        assertEquals(listOf(episodes[1].id), started)
        owner.clearSession()
    }

    @Test
    fun `episodes go on to the next whatever the device's play mode`() = runTest {
        val owner = testViewerOwner(SavedStateHandle(), scopeOverride = this, playbackModeProvider = { ViewerPlaybackMode.LOOP })
        owner.replaceSession(session("watching", episodes).copy(series = ViewerSeries.Watching()))
        assertEquals(ViewerPlaybackMode.NEXT, owner.state.value.controls.playbackMode)
        owner.clearSession()
        owner.replaceSession(session("feed", listOf(post("clip"))))
        assertEquals(ViewerPlaybackMode.LOOP, owner.state.value.controls.playbackMode)
        owner.clearSession()
    }

    @Test
    fun `an episode's lookup may take half a minute where a post's gives up`() = runTest {
        val resolver = ViewerPostResolver { _, id ->
            delay(35_000)
            post(id.sourcePostId, id.source)
        }
        val watching = testViewerOwner(SavedStateHandle(), postResolver = resolver, scopeOverride = this)
        watching.replaceSession(session("watching", episodes.take(1)).copy(series = ViewerSeries.Watching()))
        advanceUntilIdle()
        assertEquals(ViewerResolutionStatus.RESOLVED, watching.state.value.currentPage?.resolution?.status)
        watching.clearSession()

        val feed = testViewerOwner(SavedStateHandle(), postResolver = resolver, scopeOverride = this)
        feed.replaceSession(session("feed", episodes.take(1)))
        advanceUntilIdle()
        assertEquals(ViewerResolutionStatus.FAILED, feed.state.value.currentPage?.resolution?.status)
        feed.clearSession()
    }

    @Test
    fun `leaving while an episode is looked up cancels the lookup`() = runTest {
        var cancelled = false
        val owner = testViewerOwner(SavedStateHandle(), postResolver = ViewerPostResolver { _, _ ->
            try {
                delay(35_000)
                null
            } finally {
                cancelled = true
            }
        }, scopeOverride = this)
        owner.replaceSession(session("watching", episodes.take(1)).copy(series = ViewerSeries.Watching()))
        advanceTimeBy(3_000)
        owner.clearSession()
        runCurrent()
        assertTrue(cancelled)
    }

    @Test
    fun `a drag toward a neighbouring episode does not look it up`() = runTest {
        val started = mutableListOf<PostId>()
        val owner = testViewerOwner(SavedStateHandle(), postResolver = ViewerPostResolver { _, id ->
            started += id
            null
        }, scopeOverride = this)
        owner.replaceSession(session("watching", episodes).copy(series = ViewerSeries.Watching()))
        advanceUntilIdle()
        owner.onAction(ViewerAction.RequestPageResolution(episodes[1].id))
        advanceUntilIdle()
        assertEquals(listOf(episodes[0].id), started)
        assertEquals(ViewerResolutionStatus.IDLE, owner.state.value.pages[1].resolution.status)
        owner.clearSession()
    }

    @Test
    fun `the next episode is looked up once the current one passes its watched point`() = runTest {
        val started = mutableListOf<PostId>()
        val owner = testViewerOwner(SavedStateHandle(), postResolver = ViewerPostResolver { _, id ->
            started += id
            null
        }, scopeOverride = this)
        owner.replaceSession(session("watching", episodes).copy(series = ViewerSeries.Watching()))
        advanceUntilIdle()
        val report: suspend (Post, ViewerPlaybackProgress, ViewerSession) -> Unit = { _, _, _ -> }
        owner.recordPlaybackProgress(episodes[0], ViewerPlaybackProgress(1_200_000, 1_440_000), report)
        advanceUntilIdle()
        assertEquals("Before 85% only the episode on screen resolves", listOf(episodes[0].id), started)

        owner.recordPlaybackProgress(episodes[0], ViewerPlaybackProgress(1_224_000, 1_440_000), report)
        advanceUntilIdle()
        assertEquals(listOf(episodes[0].id, episodes[1].id), started)
        owner.onAction(ViewerAction.RequestPageResolution(episodes[2].id))
        advanceUntilIdle()
        assertEquals(listOf(episodes[0].id, episodes[1].id), started)
        owner.clearSession()
    }

    @Test
    fun `a chapter's lookup keeps a post's limit`() = runTest {
        val owner = testViewerOwner(SavedStateHandle(), postResolver = ViewerPostResolver { _, id ->
            delay(35_000)
            post(id.sourcePostId, id.source)
        }, scopeOverride = this)
        owner.replaceSession(session("reading", episodes.take(1)).copy(series = ViewerSeries.Reading(GalleryReadingMode.VERTICAL)))
        advanceUntilIdle()
        assertEquals(ViewerResolutionStatus.FAILED, owner.state.value.currentPage?.resolution?.status)
        owner.clearSession()
    }

    @Test
    fun `progress reported as the Viewer closes still reaches the closed session`() = runTest {
        val episode = post("episode", media = listOf(ViewerOwnerTestData.video("episode.mp4")))
        val recorded = mutableListOf<Pair<String, Long>>()
        val record: suspend (Post, ViewerPlaybackProgress, ViewerSession) -> Unit = { _, progress, session ->
            recorded += session.sessionId to progress.positionMs
        }
        val owner = testViewerOwner(SavedStateHandle(), scopeOverride = this)
        val host = ViewerScreenHost(onAction = owner::onAction, onPlaybackProgress = { post, progress -> owner.recordPlaybackProgress(post, progress, record) })
        owner.replaceSession(session("watching", listOf(episode)).copy(series = ViewerSeries.Watching()))
        requireNotNull(host.episodeProgress(owner.state.value.series, episode))(ViewerPlaybackProgress(4_000, 1_440_000))
        owner.clearSession(retainPresentation = true)
        requireNotNull(host.episodeProgress(owner.state.value.series, episode))(ViewerPlaybackProgress(6_000, 1_440_000))
        advanceUntilIdle()
        assertEquals(listOf("watching" to 4_000L, "watching" to 6_000L), recorded)
    }

    @Test
    fun `closing after a replay only pauses the closing player`() = runTest {
        val owner = testViewerOwner(SavedStateHandle(), scopeOverride = this)
        val episode = post("episode", media = listOf(ViewerOwnerTestData.video("episode.mp4")))
        owner.replaceSession(session("watching", listOf(episode)).copy(series = ViewerSeries.Watching()))
        owner.onAction(ViewerAction.RestartPlayback)
        val playing = owner.state.value.controls.playback
        owner.clearSession(retainPresentation = true)
        assertEquals(1L, playing.restartRequest)
        assertEquals(playing.copy(playing = false), owner.state.value.controls.playback)
    }

    @Test
    fun `a closing Viewer's paused player gives up the system's media controls`() = runTest {
        val owner = testViewerOwner(SavedStateHandle(), scopeOverride = this)
        val episode = post("episode", media = listOf(ViewerOwnerTestData.video("episode.mp4")))
        owner.replaceSession(session("watching", listOf(episode)).copy(series = ViewerSeries.Watching()))
        fun frame() = owner.state.value.let { state ->
            ViewerFrame(
                state, viewerPostNavigation(state), listOf(episode), ViewerSelection(episode, episode.media, 0, false, emptyList()),
                chromeVisibleState = mutableStateOf(false), chromeStage = mutableIntStateOf(0), opening = false, returning = false, inPictureInPicture = false,
                touchExplorationEnabled = false, isLandscape = false, videoRepeatsState = mutableStateOf(false),
            )
        }
        assertTrue(frame().systemControlsReady)
        owner.clearSession(retainPresentation = true)
        assertFalse(frame().systemControlsReady)
    }

    @Test
    fun `a watched session's seeds arrive with its first state, before anything can save`() = runTest {
        val seeds = ViewerSeries.Watching(mapOf(episodes[0].id to 90_000L))
        val owner = testViewerOwner(SavedStateHandle(), scopeOverride = this)
        val states = mutableListOf<com.theoriacodex.app.viewer.state.ViewerUiState>()
        val collecting = launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { owner.state.collect { states += it } }
        owner.replaceSession(session("watching", episodes).copy(series = seeds))
        collecting.cancel()
        assertTrue(states.filter { it.session != null }.all { it.series == seeds })
        val positions = ViewerVideoPositionStore().forSession(states.last().session, states.last().series as? ViewerSeries.Watching)
        positions.retainAround(episodes.map(Post::id), 0)
        assertEquals(90_000L, positions.position(com.theoriacodex.app.viewer.state.ViewerMediaKey(episodes[0].id, 0)))
        owner.clearSession()
    }

    @Test
    fun `picking a stream after one failed clears the failure and plays on, keeping the choice for the session`() {
        val identity = ViewerSessionIdentity("watching")
        val episode = post("episode", media = listOf(ViewerOwnerTestData.video("episode.mp4")))
        var state = createViewerUiState(identity, listOf(episode))
        val key = requireNotNull(state.currentMedia).key
        state = reduceViewerState(state, ViewerAction.ShowPanel(ViewerPanel.EPISODE_STREAM)).state
        state = reduceViewerState(state, ViewerAction.MediaFailed(identity, ViewerMediaError.Recoverable(key, "Could not play video"))).state
        assertFalse(state.controls.playback.playing)

        state = reduceViewerState(state, ViewerAction.ChooseStream("720p · B")).state
        assertNull(state.mediaError)
        assertTrue(state.controls.playback.playing)
        assertNull(state.controls.panel)
        assertEquals("720p · B", state.controls.episode.streamLabel)
        state = reduceViewerState(state, ViewerAction.ChooseSubtitle(ViewerSubtitleChoice.Track("English"))).state
        assertEquals(ViewerEpisodeChoice("720p · B", ViewerSubtitleChoice.Track("English"), episodeStreams = state.controls.episode.episodeStreams), state.controls.episode)
    }

    @Test
    fun `the next player gets the subtitles and audio chosen on the last, by label`() {
        val choice = ViewerEpisodeChoice(subtitle = ViewerSubtitleChoice.Track("English"), audioLabel = "Japanese")
        val next = PlaybackTracks(
            subtitles = listOf(PlaybackTrack("0:1/0", "Español", selected = false), PlaybackTrack("0:2/0", "English", selected = false)),
            audio = listOf(PlaybackTrack("1:0/0", "English", selected = true), PlaybackTrack("2:0/0", "Japanese", selected = false)),
        )
        assertEquals(listOf(ViewerTrackChange.Subtitle("0:2/0"), ViewerTrackChange.Audio("2:0/0")), choice.trackChanges(next))
        val applied = next.copy(
            subtitles = next.subtitles.map { it.copy(selected = it.label == "English") },
            audio = next.audio.map { it.copy(selected = it.label == "Japanese") },
        )
        assertEquals(emptyList<ViewerTrackChange>(), choice.trackChanges(applied))
        assertEquals(emptyList<ViewerTrackChange>(), ViewerEpisodeChoice(audioLabel = "Korean").trackChanges(applied))
        val defaulted = next.copy(subtitles = next.subtitles.map { it.copy(selected = it.label == "Español") })
        assertEquals(listOf(ViewerTrackChange.Subtitle(null)), ViewerEpisodeChoice(subtitle = ViewerSubtitleChoice.Off).trackChanges(defaulted))
    }

    @Test
    fun `with no choice, the subtitle in the device's language shows, else the first, unless Media3 shows one`() {
        fun subtitles(vararg labels: String) = PlaybackTracks(subtitles = labels.mapIndexed { index, label -> PlaybackTrack("$index:/0", label, selected = false) })
        val none = ViewerEpisodeChoice()
        assertEquals(listOf(ViewerTrackChange.Subtitle("1:/0")), none.trackChanges(subtitles("Signs", "English [CC]"), Locale.US))
        assertEquals(listOf(ViewerTrackChange.Subtitle("2:/0")), none.trackChanges(subtitles("English", "Signs", "ESPAÑOL"), Locale.forLanguageTag("es-PR")))
        assertEquals(listOf(ViewerTrackChange.Subtitle("1:/0")), none.trackChanges(subtitles("Signs", "pt-BR", "Portugal"), Locale.forLanguageTag("pt")))
        assertEquals(listOf(ViewerTrackChange.Subtitle("0:/0")), none.trackChanges(subtitles("Signs", "Englishman"), Locale.US))
        val shownByMedia3 = PlaybackTracks(subtitles = listOf(PlaybackTrack("0:/0", "English", selected = false), PlaybackTrack("subtitle:0/0", "Signs", selected = true)))
        assertEquals(emptyList<ViewerTrackChange>(), none.trackChanges(shownByMedia3, Locale.US))
        assertEquals(emptyList<ViewerTrackChange>(), ViewerEpisodeChoice(subtitle = ViewerSubtitleChoice.Off).trackChanges(subtitles("English"), Locale.US))
    }

    @Test
    fun `a chosen subtitle the next episode lacks shows its default instead of none`() {
        val english = ViewerEpisodeChoice(subtitle = ViewerSubtitleChoice.Track("English"))
        val next = PlaybackTracks(subtitles = listOf(PlaybackTrack("0:/0", "Español", selected = false), PlaybackTrack("1:/0", "English [CC]", selected = false)))
        assertEquals(listOf(ViewerTrackChange.Subtitle("1:/0")), english.trackChanges(next, Locale.US))
        val later = PlaybackTracks(subtitles = listOf(PlaybackTrack("0:/0", "English [CC]", selected = true), PlaybackTrack("1:/0", "English", selected = false)))
        assertEquals(listOf(ViewerTrackChange.Subtitle("1:/0")), english.trackChanges(later, Locale.US))
    }


    @Test
    fun `playback is reported every ten seconds while playing and once when it stops, never without a duration`() = runTest {
        val playing = MutableStateFlow(false)
        var positionMs = 0L
        var durationMs = 0L
        val reports = mutableListOf<Long>()
        val job = launch {
            reportPlaybackProgress(playing, { ViewerPlaybackProgress(positionMs, durationMs).takeIf { durationMs > 0 } }) {
                reports += it.positionMs
            }
        }
        runCurrent()
        assertEquals(emptyList<Long>(), reports)

        durationMs = 1_440_000
        playing.value = true
        runCurrent()
        positionMs = 9_000
        advanceTimeBy(9_999)
        assertEquals(emptyList<Long>(), reports)
        positionMs = 10_000
        advanceTimeBy(2)
        positionMs = 20_000
        advanceTimeBy(10_000)
        assertEquals(listOf(10_000L, 20_000L), reports)

        positionMs = 25_000
        playing.value = false
        runCurrent()
        advanceTimeBy(60_000)
        assertEquals(listOf(10_000L, 20_000L, 25_000L), reports)
        job.cancel()
    }

    @Test
    fun `a fallback stream plays for its episode only, a hand-picked one is remembered, and Retry leaves the episode's ask to its player`() {
        val identity = ViewerSessionIdentity("watching")
        val streams = listOf("A", "B").map { com.theoriacodex.domain.model.MediaStream("https://video.test/$it.m3u8", it) }
        val episodes = (1..2).map { post("episode-$it", media = listOf(com.theoriacodex.domain.model.ImageRef(null, null, "video/mp4", streams = streams))) }
        var state = createViewerUiState(identity, episodes, series = ViewerSeries.Watching())
        val first = requireNotNull(state.currentMedia).key
        val second = com.theoriacodex.app.viewer.state.ViewerMediaKey(episodes[1].id, 0)

        state = reduceViewerState(state, ViewerAction.FallBackToStream(first, "B")).state
        assertEquals("B", state.controls.episode.streamLabelFor(first))
        assertNull(state.controls.episode.streamLabelFor(second))
        assertNull(state.controls.episode.pickedStream(first))

        state = reduceViewerState(state, ViewerAction.FallBackToStream(second, "A")).state
        assertEquals("B", state.controls.episode.streamLabelFor(first))
        assertEquals("A", state.controls.episode.streamLabelFor(second))

        state = reduceViewerState(state, ViewerAction.ChooseStream("A")).state
        assertEquals("A", state.controls.episode.streamLabelFor(first))
        assertEquals("A", state.controls.episode.pickedStream(first))
        assertEquals("A", state.controls.episode.streamLabelFor(second))

        state = reduceViewerState(state, ViewerAction.MediaFailed(identity, ViewerMediaError.Recoverable(first, "Could not play video"))).state
        val retried = reduceViewerState(state, ViewerAction.RetryMedia)
        assertEquals(1L, retried.state.currentMedia?.loadGeneration)
        assertEquals(emptyList<Any>(), retried.effects)
    }
}
