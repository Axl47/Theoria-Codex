package com.theoriacodex.app.viewer.state

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ViewerSlideshowTest {
    private val session = ViewerSessionIdentity(value = "slideshow", queryHash = "query")

    @Test
    fun `a still image moves on after five seconds and the slideshow hides the chrome`() = runTest {
        var state = started(post("a", "image/jpeg"), post("b", "image/jpeg")).ready()
        assertFalse(state.controls.chromeVisible)
        val actions = mutableListOf<ViewerAction>()
        val held = state
        launch { holdViewerSlideshowImage(held, actions::add) }
        advanceTimeBy(VIEWER_SLIDESHOW_IMAGE_MS - 1)
        runCurrent()
        assertTrue(actions.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        state = reduceViewerState(state, actions.single()).state
        assertEquals(1, state.currentPageIndex)
        assertTrue(state.controls.slideshow)
    }

    @Test
    fun `an image waits until it has loaded before its time starts`() {
        assertNull(started(post("a", "image/jpeg")).slideshowImageHold())
    }

    @Test
    fun `a video is not timed and moves on when it ends even in loop mode`() = runTest {
        var state = started(post("a", "video/mp4"), post("b", "image/jpeg")).ready()
        assertEquals(ViewerPlaybackMode.LOOP, state.controls.playbackMode)
        assertNull(state.slideshowImageHold())
        val actions = mutableListOf<ViewerAction>()
        holdViewerSlideshowImage(state, actions::add)
        assertTrue(actions.isEmpty())
        val media = requireNotNull(state.currentMedia)
        state = reduceViewerState(state, ViewerAction.PlaybackCompleted(session, media.key, media.loadGeneration)).state
        assertEquals(1, state.currentPageIndex)
        assertEquals(ViewerPlaybackMode.LOOP, state.controls.playbackMode)
    }

    @Test
    fun `stopping ignores a hold that was already running`() {
        var state = started(post("a", "image/jpeg"), post("b", "image/jpeg")).ready()
        val key = requireNotNull(state.slideshowImageHold())
        state = reduceViewerState(state, ViewerAction.SetSlideshow(false)).state
        assertNull(state.slideshowImageHold())
        state = reduceViewerState(state, ViewerAction.SlideshowAdvance(session, key)).state
        assertEquals(0, state.currentPageIndex)
    }

    @Test
    fun `more posts arriving for the same session keep the slideshow running and a new session starts without one`() {
        val posts = listOf(post("a", "image/jpeg"), post("b", "image/jpeg"))
        val state = started(posts[0])
        fun replaced(identity: ViewerSessionIdentity) =
            reduceViewerState(state, ViewerAction.ReplaceSession(session = identity, posts = posts, initialPageIndex = 0)).state

        assertTrue(replaced(session).controls.slideshow)
        assertFalse(replaced(session.copy(value = "other")).controls.slideshow)
    }

    @Test
    fun `the slideshow stops at the end of the queue`() {
        var state = started(post("a", "image/jpeg")).ready()
        state = reduceViewerState(state, ViewerAction.SlideshowAdvance(session, requireNotNull(state.currentMedia).key)).state
        assertEquals(0, state.currentPageIndex)
        assertFalse(state.controls.slideshow)
    }

    private fun started(vararg posts: Post): ViewerUiState =
        reduceViewerState(createViewerUiState(session, posts.toList()), ViewerAction.SetSlideshow(true)).state

    private fun ViewerUiState.ready(): ViewerUiState {
        val media = requireNotNull(currentMedia)
        return reduceViewerState(this, ViewerAction.MediaReady(this@ViewerSlideshowTest.session, media.key, media.loadGeneration)).state
    }

    private fun post(id: String, mime: String): Post {
        val ref = ImageRef(url = "https://media.example/$id", localPath = null, mime = mime, progressiveUrls = emptyList())
        return Post(
            id = PostId(SourceKey.PIXIV, id), preview = ref, full = ref, media = listOf(ref), pageUrl = null,
            width = 100, height = 200, canonicalTags = emptyList(), rawTags = emptyList(), authorName = null, createdAtEpochMs = 1L,
        )
    }
}
