package com.theoriacodex.app.viewer

import androidx.lifecycle.SavedStateHandle
import com.theoriacodex.app.viewer.ViewerOwnerTestData.post
import com.theoriacodex.app.viewer.ViewerOwnerTestData.session
import com.theoriacodex.app.viewer.state.ViewerPlaybackMode
import com.theoriacodex.app.viewer.state.ViewerResolutionStatus
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
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules a series being watched follows: one episode at a time, played through, its progress reported. */
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
    fun `a stream is recovered once until it plays again`() {
        val requests = mutableSetOf<String>()
        var asked = 0
        val recovery = ViewerStreamRecovery(requests, "stream:episode:0") { asked++ }
        assertTrue(recovery.request())
        assertFalse(recovery.request())
        assertEquals(1, asked)
        // A player rebuilt for the recovered stream shares the budget through the page's set.
        assertFalse(ViewerStreamRecovery(requests, "stream:episode:0") { asked++ }.request())
        recovery.played()
        assertTrue(recovery.request())
        assertEquals(2, asked)
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
}
