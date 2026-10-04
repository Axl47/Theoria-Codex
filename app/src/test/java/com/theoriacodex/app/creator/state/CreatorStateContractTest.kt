package com.theoriacodex.app.creator.state

import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.search.SearchVisibilityFilters
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CreatorStateContractTest {
    private val creator = CreatorProfile(SourceKey.PIXIV, "Artist", "artist-id", uploadsQuery = "artist-id")

    @Test
    fun `open and refresh carry immutable provider inputs`() {
        val initial = CreatorUiState()
        val opened = initial.reduce(CreatorAction.OpenCreator(creator))
        val request = requireNotNull(opened.state.activeRequest)
        assertEquals(CreatorEmptyReason.NO_CREATOR, initial.emptyReason)
        assertEquals(creator, opened.state.creator)
        assertEquals("creator:PIXIV:artist-id", opened.state.queryHash)
        assertEquals(CreatorEffect.LoadPage(request, creator, null), opened.effect)
        assertTrue(opened.state.isRefreshing)
        assertNull(opened.state.emptyReason)
        assertNull(opened.state.errorMessage)

        val failed = opened.state.reduce(CreatorAction.RequestFailed(
            request, "Not available", CreatorFailureReason.UNSUPPORTED_SOURCE,
        )).state
        assertFalse(failed.isRefreshing)
        assertEquals(CreatorEmptyReason.UNSUPPORTED_SOURCE, failed.emptyReason)
        assertTrue(failed.results.isEmpty())
        assertTrue(failed.reduce(CreatorAction.Refresh).state.isRefreshing)
    }

    @Test
    fun `page failure preserves order and exact token until explicit retry succeeds`() {
        val sparse = testPost(sourcePostId = "1", title = null)
        val hydrated = sparse.copy(title = "Hydrated")
        val next = testPost(sourcePostId = "2")
        val loaded = loadedState(listOf(sparse), "page-2")
        val started = loaded.reduce(CreatorAction.LoadNextPage)
        val request = requireNotNull(started.state.activeRequest)
        val failed = started.state.reduce(CreatorAction.RequestFailed(request, "network failed")).state
        assertEquals(listOf(sparse), failed.results)
        assertEquals("page-2", failed.nextPageToken)
        assertNull(failed.reduce(CreatorAction.LoadNextPage).effect)
        val retrying = failed.reduce(CreatorAction.RetryPage)
        val retry = requireNotNull(retrying.state.activeRequest)
        assertEquals(CreatorEffect.LoadPage(retry, creator, "page-2"), retrying.effect)
        val completed = retrying.state.reduce(CreatorAction.PageLoaded(retry, Page(listOf(hydrated, next), null))).state
        assertEquals(listOf(hydrated, next), completed.results)
        assertFalse(completed.isPaging)
        assertFalse(completed.canLoadMore)
        assertNull(completed.errorMessage)
        assertNull(completed.emptyReason)
    }

    @Test
    fun `new creator rejects stale completion failure and cancellation`() {
        val first = CreatorUiState().reduce(CreatorAction.OpenCreator(creator)).state
        val oldRequest = requireNotNull(first.activeRequest)
        val replacement = creator.copy(profileId = "new", uploadsQuery = "new")
        val second = first.reduce(CreatorAction.OpenCreator(replacement)).state
        assertEquals(second, second.reduce(CreatorAction.PageLoaded(oldRequest, Page(listOf(testPost()), null))).state)
        assertEquals(second, second.reduce(CreatorAction.RequestFailed(oldRequest, "stale")).state)
        assertEquals(second, second.reduce(CreatorAction.RequestCancelled(oldRequest)).state)
        assertTrue(requireNotNull(second.activeRequest).generation > oldRequest.generation)
    }

    @Test
    fun `cancelled page retains continuation and unsupported source invalidates it`() {
        val started = loadedState(listOf(testPost()), "next").reduce(CreatorAction.LoadNextPage).state
        val cancelled = started.reduce(CreatorAction.RequestCancelled(requireNotNull(started.activeRequest))).state
        assertFalse(cancelled.isPaging)
        assertTrue(cancelled.canLoadMore)
        assertNull(cancelled.errorMessage)
        val unsupported = cancelled.reduce(CreatorAction.SourceUnavailable).state
        assertTrue(unsupported.results.isEmpty())
        assertFalse(unsupported.canLoadMore)
        assertEquals(CreatorFailureReason.UNSUPPORTED_SOURCE, unsupported.failureReason)
    }

    @Test
    fun `empty success and failed root remain distinct`() {
        val started = CreatorUiState().reduce(CreatorAction.OpenCreator(creator)).state
        val request = requireNotNull(started.activeRequest)
        val empty = started.reduce(CreatorAction.PageLoaded(request, Page(emptyList(), null))).state
        val failed = started.reduce(CreatorAction.RequestFailed(request, "network failed")).state
        assertEquals(CreatorEmptyReason.NO_RESULTS, empty.emptyReason)
        assertNull(failed.emptyReason)
        assertEquals(CreatorFailureReason.REQUEST_FAILED, failed.failureReason)
    }

    @Test
    fun `Viewer launch retains filtered ordering and creator identity`() {
        val posts = listOf(testPost(sourcePostId = "1"), testPost(sourcePostId = "2"))
        val filters = SearchVisibilityFilters(hideLiked = true)
        val effect = loadedState(posts).reduce(CreatorAction.OpenResult(
            index = 0, scrollOffsetHint = 25, visibleResults = listOf(posts.last()), visibilityFilters = filters,
        )).effect as CreatorEffect.OpenViewer
        assertEquals(listOf(posts.last()), effect.posts)
        assertEquals("creator:PIXIV:artist-id", effect.context.queryHash)
        assertEquals(25, effect.context.scrollOffsetHint)
        assertEquals(ViewerStreamSource.CREATOR_PROFILE, effect.context.streamSource)
        assertEquals(filters, effect.visibilityFilters)
    }

    private fun loadedState(posts: List<com.theoriacodex.domain.model.Post>, token: String? = null): CreatorUiState {
        val started = CreatorUiState().reduce(CreatorAction.OpenCreator(creator)).state
        return started.reduce(CreatorAction.PageLoaded(requireNotNull(started.activeRequest), Page(posts, token))).state
    }
}
