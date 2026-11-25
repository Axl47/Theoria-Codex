package com.theoriacodex.app.viewer.state

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerClipDwellTest {
    private val session = ViewerSessionIdentity(value = "dwell", queryHash = "query")

    @Test fun `a one second clip replays until five seconds and moves on at a loop's end (ADR 0217)`() {
        var state = playNext().act(ViewerAction.RestartDwell(0L))
        (1..4).forEach { second ->
            val restarts = state.controls.playback.restartRequest
            state = state.complete(second * 1_000L)
            assertEquals("still on the first post after $second s", 0, state.currentPageIndex)
            assertEquals(restarts + 1, state.controls.playback.restartRequest)
        }
        state = state.complete(5_000L)
        assertEquals(1, state.currentPageIndex)
    }

    @Test fun `a six second clip moves on when it first ends (ADR 0217)`() {
        val state = playNext().act(ViewerAction.RestartDwell(0L)).complete(6_000L)
        assertEquals(1, state.currentPageIndex)
    }

    @Test fun `touching restarts the dwell (ADR 0217)`() {
        var state = playNext().act(ViewerAction.RestartDwell(0L)).complete(1_000L).complete(2_000L).complete(3_000L)
        state = state.act(ViewerAction.RestartDwell(3_500L))
        (4..8).forEach { state = state.complete(it * 1_000L) }
        assertEquals(0, state.currentPageIndex)
        assertEquals(1, state.complete(9_000L).currentPageIndex)
    }

    @Test fun `a post reached by swiping back never moves on, and a forward swipe re-arms (ADR 0217)`() {
        var state = playNext().act(ViewerAction.SelectPage(1)).act(ViewerAction.SelectPage(0)).act(ViewerAction.RestartDwell(0L))
        listOf(5_000L, 60_000L, 3_600_000L).forEach { state = state.complete(it) }
        assertEquals(0, state.currentPageIndex)
        assertTrue(state.autoAdvanceHeld)

        state = state.act(ViewerAction.SelectPage(1)).act(ViewerAction.RestartDwell(4_000_000L))
        assertFalse(state.autoAdvanceHeld)
        assertEquals(2, state.complete(4_005_000L).currentPageIndex)
    }

    @Test fun `the paused badge shows only while auto-advance is held (ADR 0217)`() {
        val held = playNext().act(ViewerAction.SelectPage(1)).act(ViewerAction.SelectPage(0))
        assertTrue(held.autoAdvanceHeld)
        assertFalse(held.act(ViewerAction.SetPlaybackMode(ViewerPlaybackMode.LOOP)).autoAdvanceHeld)
        assertFalse(held.act(ViewerAction.SelectPage(1)).autoAdvanceHeld)
        assertFalse(playNext().autoAdvanceHeld)
    }

    @Test fun `a slideshow image waits while auto-advance is held (ADR 0217)`() {
        var state = reduceViewerState(createViewerUiState(session, listOf(post("a", "image/jpeg"), post("b", "image/jpeg"))), ViewerAction.SetSlideshow(true)).state
        state = state.act(ViewerAction.SelectPage(1)).act(ViewerAction.SelectPage(0)).ready()
        assertEquals(null, state.slideshowImageHold())
    }

    private fun playNext(): ViewerUiState {
        val posts = listOf(post("a", "video/mp4"), post("b", "video/mp4"), post("c", "video/mp4"))
        return reduceViewerState(createViewerUiState(session, posts), ViewerAction.SetPlaybackMode(ViewerPlaybackMode.NEXT)).state
            .ready().act(ViewerAction.Play)
    }

    private fun ViewerUiState.act(action: ViewerAction): ViewerUiState = reduceViewerState(this, action).state

    private fun ViewerUiState.ready(): ViewerUiState {
        val media = requireNotNull(currentMedia)
        return act(ViewerAction.MediaReady(this@ViewerClipDwellTest.session, media.key, media.loadGeneration))
    }

    private fun ViewerUiState.complete(atMs: Long): ViewerUiState {
        val media = requireNotNull(currentMedia)
        val completed = act(ViewerAction.PlaybackCompleted(this@ViewerClipDwellTest.session, media.key, media.loadGeneration, atMs))
        return if (completed.currentPageIndex == currentPageIndex) completed else completed.ready().act(ViewerAction.Play)
    }

    private fun post(id: String, mime: String): Post {
        val ref = ImageRef(url = "https://media.example/$id", localPath = null, mime = mime, progressiveUrls = emptyList())
        return Post(
            id = PostId(SourceKey.PIXIV, id), preview = ref, full = ref, media = listOf(ref), pageUrl = null,
            width = 100, height = 200, canonicalTags = emptyList(), rawTags = emptyList(), authorName = null, createdAtEpochMs = 1L,
        )
    }
}
