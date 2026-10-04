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
import org.junit.Test

class ViewerRendererContractTest {
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
