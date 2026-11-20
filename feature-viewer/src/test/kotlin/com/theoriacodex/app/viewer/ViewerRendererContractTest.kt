package com.theoriacodex.app.viewer

import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.app.viewer.state.ViewerPlaybackMode
import com.theoriacodex.app.viewer.state.ViewerSessionIdentity
import com.theoriacodex.app.viewer.state.createViewerUiState
import com.theoriacodex.app.viewer.state.reduceViewerState
import com.theoriacodex.domain.model.ImageRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerRendererContractTest {
    @Test
    fun `post resolution errors do not interrupt media that is already ready`() {
        val video = ImageRef("https://example.test/clip.mp4", null, "video/mp4")
        val post = testPost().copy(full = video, media = listOf(video))
        val session = ViewerSessionIdentity("playing")
        var state = createViewerUiState(session, listOf(post))
        val key = requireNotNull(state.currentMedia).key
        state = reduceViewerState(state, ViewerAction.MediaReady(session, key, 0L)).state
        state = reduceViewerState(state, ViewerAction.ResolutionFailed(session, post.id, "Post resolution timed out", true)).state
        assertNull(state.mediaError)
        assertTrue(state.controls.playback.playing)
        state = reduceViewerState(state, ViewerAction.ResolutionFailed(session, post.id, "Source error", true)).state
        assertNull(state.mediaError)
        assertTrue(state.controls.playback.playing)
        state = reduceViewerState(state, ViewerAction.ResolutionFailed(session, post.id, "Post was deleted", false)).state
        assertNotNull(state.mediaError)
        assertFalse(state.controls.playback.playing)
    }

    @Test
    fun `media becoming ready dismisses a prior resolution timeout without pausing playback`() {
        val video = ImageRef("https://example.test/clip.mp4", null, "video/mp4")
        val post = testPost().copy(full = video, media = listOf(video))
        val session = ViewerSessionIdentity("loading")
        var state = createViewerUiState(session, listOf(post))
        val key = requireNotNull(state.currentMedia).key
        state = reduceViewerState(state, ViewerAction.ResolutionFailed(session, post.id, "Post resolution timed out", true)).state
        assertNotNull(state.mediaError)
        assertTrue(state.controls.playback.playing)
        state = reduceViewerState(state, ViewerAction.MediaReady(session, key, 0L)).state
        assertNull(state.mediaError)
        assertTrue(state.currentMedia?.ready == true)
        assertTrue(state.controls.playback.playing)
    }

    @Test
    fun `late renderer events cannot fail clear or complete a newer media generation`() {
        val video = ImageRef("https://example.test/clip.mp4", null, "video/mp4")
        val post = testPost().copy(full = video, media = listOf(video))
        var state = createViewerUiState(ViewerSessionIdentity("session"), listOf(post))
        state = reduceViewerState(state, ViewerAction.SetPlaybackMode(ViewerPlaybackMode.ONCE)).state
        fun events(): ViewerRendererEvents = viewerRendererEvents(
            requireNotNull(state.session), requireNotNull(state.currentMedia).key,
            requireNotNull(state.currentMedia).loadGeneration,
            onAction = { state = reduceViewerState(state, it).state },
            onTimelineInteractionActiveChanged = {}, onDurationKnown = {},
        )
        val old = events()
        old.onError("failed")
        assertNotNull(state.mediaError)
        assertFalse(state.controls.playback.playing)
        state = reduceViewerState(state, ViewerAction.RetryMedia).state
        assertTrue(state.controls.playback.playing)
        assertFalse(requireNotNull(state.currentMedia).ready)
        val retried = state
        old.onError("late failure")
        old.onPlaybackCompleted()
        assertEquals(retried, state)
        val current = events()
        current.onError("new failure")
        val failed = state
        old.onReady()
        assertEquals(failed, state)
        current.onReady()
        assertNull(state.mediaError)
        state = reduceViewerState(state, ViewerAction.ReplaceSession(ViewerSessionIdentity("replacement"), listOf(post))).state
        val replacement = state
        current.onError("old session")
        current.onReady()
        current.onPlaybackCompleted()
        assertEquals(replacement, state)
    }
}
