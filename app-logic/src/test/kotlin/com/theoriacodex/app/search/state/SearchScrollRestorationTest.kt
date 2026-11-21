package com.theoriacodex.app.search.state

import com.theoriacodex.data.repository.SearchScrollState
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchScrollRestorationTest {
    private val pending = SearchRestorationUiState.Restored(false, SearchScrollState(22, 17), 1L)
    private val state = SearchUiState(restoration = pending,
        content = SearchContentUiState(hasExecutedSearch = true, canLoadMore = true))

    @Test fun `missing anchor waits for continuation and exhaustion permits empty fallback`() {
        assertFalse(state.canApplyScrollRestoration)
        assertTrue(state.copy(content = state.content.copy(canLoadMore = false)).canApplyScrollRestoration)
    }

    @Test fun `loading and failure are not evidence of exhaustion`() {
        val exhausted = state.copy(content = state.content.copy(canLoadMore = false))
        for (kind in listOf(SearchRequestKind.INITIAL, SearchRequestKind.PAGE)) {
            assertFalse(exhausted.copy(execution = SearchExecutionUiState(SearchRequest(1, kind, emptySearchQuery()))).canApplyScrollRestoration)
        }
        assertFalse(exhausted.copy(content = exhausted.content.copy(hasExecutedSearch = false)).canApplyScrollRestoration)
        assertFalse(exhausted.copy(content = exhausted.content.copy(
            error = SearchErrorUiState("Retry", SearchRequestKind.PAGE, true),
        )).canApplyScrollRestoration)
    }

    @Test fun `only acknowledged restoration allows scroll persistence`() {
        assertFalse(SearchRestorationUiState.NotStarted.allowsScrollPersistence)
        assertFalse(SearchRestorationUiState.Restoring.allowsScrollPersistence)
        assertFalse(SearchRestorationUiState.Failed("Retry").allowsScrollPersistence)
        assertFalse(pending.allowsScrollPersistence)
        assertTrue(pending.copy(scrollState = null).allowsScrollPersistence)
    }

    @Test fun `only a request still owed a move has an open id`() {
        assertEquals(1L, pending.openScrollRequestId)
        assertEquals(null, pending.copy(scrollState = null).openScrollRequestId)
        assertEquals(null, SearchRestorationUiState.Restoring.openScrollRequestId)
    }

    // A search with 40 posts loaded, more pages available, and an open request for post 22 at 17 px.
    private fun ready(
        request: SearchRestorationUiState = pending,
        loaded: Int = 40,
        canLoadMore: Boolean = true,
        error: SearchErrorUiState? = null,
    ) = SearchUiState(restoration = request, content = SearchContentUiState(
        results = List(loaded) { post(it) }, hasExecutedSearch = true, canLoadMore = canLoadMore, error = error,
    ))

    private fun SearchUiState.step(visibilityDecided: Boolean = true, presented: Int = 40, wasShowingPosts: Boolean = false) =
        scrollStep(visibilityDecided, presented, wasShowingPosts)

    private val jump = ScrollStep.Jump(1L, 22, 17)
    private val finish = ScrollStep.Finish(1L)
    private val failure = SearchErrorUiState("Retry", SearchRequestKind.PAGE, true)
    private val reset = pending.copy(scrollState = SearchScrollState(0, 0), resetToTop = true)

    @Test fun `no open request waits`() {
        for (closed in listOf(SearchRestorationUiState.NotStarted, pending.copy(scrollState = null))) {
            assertEquals(ScrollStep.Wait, ready(closed).step())
            assertFalse(ready(closed).canApplyScrollRestoration)
        }
        val notRestored = ready(SearchRestorationUiState.NotStarted)
        assertEquals(ScrollStep.Wait, notRestored.scrollStepAfterJump(reachedTarget = true, canScrollForward = true))
    }

    @Test fun `unfinished or loading search waits even for a reset to top`() {
        for (request in listOf(pending, reset)) {
            assertEquals(ScrollStep.Wait, ready(request).let { it.copy(content = it.content.copy(hasExecutedSearch = false)) }.step())
            for (kind in listOf(SearchRequestKind.INITIAL, SearchRequestKind.PAGE)) {
                val loading = ready(request).copy(execution = SearchExecutionUiState(SearchRequest(1, kind, emptySearchQuery())))
                assertEquals(ScrollStep.Wait, loading.step())
            }
        }
    }

    @Test fun `reset to top ignores a showing failure`() {
        assertEquals(ScrollStep.Jump(1L, 0, 0), ready(reset, error = failure).step())
    }

    @Test fun `reset to top does not wait for the anchor's page`() {
        assertEquals(ScrollStep.Jump(1L, 0, 0), ready(reset, loaded = 0).step(presented = 1))
    }

    @Test fun `reset to top does not wait for duration lookups`() {
        assertEquals(ScrollStep.Jump(1L, 0, 0), ready(reset).step(visibilityDecided = false))
    }

    @Test fun `showing failure waits even when no more pages can load`() {
        assertEquals(ScrollStep.Wait, ready(error = failure, canLoadMore = false).step())
    }

    @Test fun `unloaded anchor waits for more pages and jumps once results are exhausted`() {
        assertEquals(ScrollStep.Wait, ready(loaded = 20).step(presented = 20))
        assertEquals(ScrollStep.Wait, ready(loaded = 22).step(presented = 22))
        assertEquals(jump, ready(loaded = 23).step(presented = 23))
        assertEquals(jump, ready(loaded = 20, canLoadMore = false).step(presented = 20))
    }

    @Test fun `undecided anchor visibility waits`() {
        assertEquals(ScrollStep.Wait, ready().step(visibilityDecided = false))
    }

    @Test fun `nothing presented finishes without moving`() {
        assertEquals(finish, ready().step(presented = 0))
    }

    @Test fun `keep-live request leaves a grid that was showing posts alone and still jumps one that was not`() {
        val keepLive = pending.copy(keepLiveGrid = true)
        assertEquals(finish, ready(keepLive).step(wasShowingPosts = true))
        assertEquals(jump, ready(keepLive).step(wasShowingPosts = false))
        assertEquals(jump, ready().step(wasShowingPosts = true))
    }

    @Test fun `ready request jumps to the anchor with a negative offset floored at zero`() {
        assertEquals(jump, ready().step())
        assertEquals(ScrollStep.Jump(1L, 22, 0), ready(pending.copy(scrollState = SearchScrollState(22, -5))).step())
    }

    @Test fun `jump that reached the target finishes`() {
        assertEquals(finish, ready().scrollStepAfterJump(reachedTarget = true, canScrollForward = false))
    }

    @Test fun `jump that left room to scroll forward finishes`() {
        assertEquals(finish, ready().scrollStepAfterJump(reachedTarget = false, canScrollForward = true))
    }

    @Test fun `jump stopped at the end of exhausted results finishes`() {
        assertEquals(finish, ready(canLoadMore = false).scrollStepAfterJump(reachedTarget = false, canScrollForward = false))
    }

    @Test fun `jump stopped at the list end above the target waits for the next page`() {
        assertEquals(ScrollStep.Wait, ready().scrollStepAfterJump(reachedTarget = false, canScrollForward = false))
    }

    // Whether the grid was showing posts is decided once, when the request arrives, so the next page still
    // sees false and the stopped-short jump is retried.
    @Test fun `keep-live request that arrived on a grid without posts retries a jump that stopped short`() {
        val keepLive = ready(pending.copy(keepLiveGrid = true))
        assertEquals(jump, keepLive.step(wasShowingPosts = false))
        assertEquals(ScrollStep.Wait, keepLive.scrollStepAfterJump(reachedTarget = false, canScrollForward = false))
        assertEquals(jump, ready(pending.copy(keepLiveGrid = true), loaded = 60).step(presented = 60, wasShowingPosts = false))
    }

    private fun post(index: Int) = Post(
        id = PostId(SourceKey.PIXIV, "post-$index"),
        preview = ImageRef(url = null, localPath = null, mime = null),
        full = null,
        pageUrl = null,
        width = null,
        height = null,
        canonicalTags = emptyList(),
        rawTags = emptyList(),
        authorName = null,
        createdAtEpochMs = null,
    )
}
