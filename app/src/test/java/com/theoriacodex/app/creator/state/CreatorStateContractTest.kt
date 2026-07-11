package com.theoriacodex.app.creator.state

import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.search.SearchVisibilityFilters
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CreatorStateContractTest {
    @Test
    fun `initial and open creator transitions preserve explicit identity`() {
        val initial = CreatorCoordinatorSnapshot(
            creator = null,
            queryHash = null,
            results = emptyList(),
            loading = false,
            loadingMore = false,
            canLoadMore = false,
            errorMessage = null,
        ).toUiState()
        val creator = creator()

        val opened = initial.reduce(CreatorAction.OpenCreator(creator))

        assertEquals(CreatorEmptyReason.NO_CREATOR, initial.emptyReason)
        assertEquals(creator, opened.state.creator)
        assertEquals("creator:PIXIV:artist-id", opened.state.queryHash)
        assertTrue(opened.state.isRefreshing)
        assertEquals(
            CreatorEffect.LoadCreator(requireNotNull(opened.state.activeRequest), creator),
            opened.effect,
        )
    }

    @Test
    fun `refresh and unsupported error transitions are deterministic`() {
        val state = snapshot(creator = creator(), results = listOf(testPost()))

        val refreshing = state.reduce(CreatorAction.Refresh)
        val request = requireNotNull(refreshing.state.activeRequest)
        val failed = refreshing.state.reduce(
            CreatorAction.RefreshFailed(
                request = request,
                message = "Creator browsing is not available for pixiv.",
                reason = CreatorFailureReason.UNSUPPORTED_SOURCE,
            )
        )

        assertTrue(refreshing.state.isRefreshing)
        assertEquals(CreatorEffect.Refresh(request), refreshing.effect)
        assertFalse(failed.state.isRefreshing)
        assertEquals(CreatorEmptyReason.UNSUPPORTED_SOURCE, failed.state.emptyReason)
        assertEquals(CreatorFailureReason.UNSUPPORTED_SOURCE, failed.state.failureReason)
        assertTrue(failed.state.results.isEmpty())
    }

    @Test
    fun `empty and generic error snapshots remain distinct`() {
        val empty = snapshot(creator = creator())
        val failed = snapshot(creator = creator(), errorMessage = "network failed")

        assertEquals(CreatorEmptyReason.NO_RESULTS, empty.emptyReason)
        assertNull(failed.emptyReason)
        assertEquals("network failed", failed.errorMessage)
    }

    @Test
    fun `paging merges duplicate identities and retains incoming hydration`() {
        val sparse = testPost(sourcePostId = "1", title = null)
        val hydrated = sparse.copy(title = "Hydrated")
        val next = testPost(sourcePostId = "2")
        val state = snapshot(creator = creator(), results = listOf(sparse), canLoadMore = true)

        val started = state.reduce(CreatorAction.LoadNextPage)
        val request = requireNotNull(started.state.activeRequest)
        val completed = started.state.reduce(
            CreatorAction.PageLoaded(
                request = request,
                posts = listOf(hydrated, next),
                canLoadMore = false,
            )
        )

        assertTrue(started.state.isPaging)
        assertEquals(CreatorEffect.LoadNextPage(request), started.effect)
        assertEquals(listOf(hydrated, next), completed.state.results)
        assertFalse(completed.state.isPaging)
        assertFalse(completed.state.canLoadMore)
        assertNull(completed.state.emptyReason)
        assertNull(completed.state.activeRequest)
    }

    @Test
    fun `cancelled creator page can retry and rejects stale page failure`() {
        val state = snapshot(
            creator = creator(),
            results = listOf(testPost(sourcePostId = "existing")),
            canLoadMore = true,
        )
        val first = state.reduce(CreatorAction.LoadNextPage)
        val firstRequest = requireNotNull(first.state.activeRequest)
        val cancelled = first.state.reduce(CreatorAction.RequestCancelled(firstRequest)).state
        val second = cancelled.reduce(CreatorAction.LoadNextPage)
        val secondRequest = requireNotNull(second.state.activeRequest)

        val afterStaleFailure = second.state.reduce(
            CreatorAction.PageFailed(firstRequest, "stale page failure")
        ).state

        assertEquals(second.state, afterStaleFailure)
        assertTrue(secondRequest.generation > firstRequest.generation)

        val completed = afterStaleFailure.reduce(
            CreatorAction.PageLoaded(
                request = secondRequest,
                posts = listOf(testPost(sourcePostId = "current")),
                canLoadMore = false,
            )
        ).state
        assertEquals(listOf("existing", "current"), completed.results.map { it.id.sourcePostId })
        assertNull(completed.activeRequest)
    }

    @Test
    fun `new creator generation rejects stale completion failure and cancellation`() {
        val firstCreator = creator()
        val secondCreator = creator().copy(profileId = "second", uploadsQuery = "second")
        val initial = CreatorUiState()
        val first = initial.reduce(CreatorAction.OpenCreator(firstCreator))
        val firstRequest = requireNotNull(first.state.activeRequest)
        val second = first.state.reduce(CreatorAction.OpenCreator(secondCreator))
        val secondRequest = requireNotNull(second.state.activeRequest)
        val staleSnapshot = coordinatorSnapshot(
            creator = firstCreator,
            results = listOf(testPost(sourcePostId = "stale")),
        )

        val afterStaleCompletion = second.state.reduce(
            CreatorAction.RefreshCompleted(firstRequest, staleSnapshot)
        ).state
        val afterStaleFailure = afterStaleCompletion.reduce(
            CreatorAction.RefreshFailed(firstRequest, "stale failure")
        ).state
        val afterStaleCancellation = afterStaleFailure.reduce(
            CreatorAction.RequestCancelled(firstRequest)
        ).state

        assertEquals(second.state, afterStaleCompletion)
        assertEquals(second.state, afterStaleFailure)
        assertEquals(second.state, afterStaleCancellation)
        assertTrue(secondRequest.generation > firstRequest.generation)

        val completed = afterStaleCancellation.reduce(
            CreatorAction.RefreshCompleted(
                request = secondRequest,
                snapshot = coordinatorSnapshot(
                    creator = secondCreator,
                    results = listOf(testPost(sourcePostId = "current")),
                ),
            )
        ).state
        assertEquals(secondCreator, completed.creator)
        assertEquals("current", completed.results.single().id.sourcePostId)
        assertNull(completed.activeRequest)
    }

    @Test
    fun `refresh keeps loading empty and generic error lanes mutually exclusive`() {
        val empty = snapshot(creator = creator())

        val refreshing = empty.reduce(CreatorAction.Refresh)
        val request = requireNotNull(refreshing.state.activeRequest)

        assertTrue(refreshing.state.isRefreshing)
        assertNull(refreshing.state.emptyReason)
        assertNull(refreshing.state.errorMessage)
        assertNull(refreshing.state.failureReason)

        val failed = refreshing.state.reduce(
            CreatorAction.RefreshFailed(request, "network failed")
        ).state

        assertFalse(failed.isRefreshing)
        assertEquals("network failed", failed.errorMessage)
        assertEquals(CreatorFailureReason.REQUEST_FAILED, failed.failureReason)
        assertNull(failed.emptyReason)

        val retrying = failed.reduce(CreatorAction.Refresh)
        val cancelled = retrying.state.reduce(
            CreatorAction.RequestCancelled(requireNotNull(retrying.state.activeRequest))
        ).state
        assertFalse(cancelled.isRefreshing)
        assertNull(cancelled.errorMessage)
        assertEquals(CreatorEmptyReason.NO_RESULTS, cancelled.emptyReason)
    }

    @Test
    fun `open result produces creator viewer launch intent`() {
        val posts = listOf(testPost(sourcePostId = "1"), testPost(sourcePostId = "2"))
        val visiblePosts = listOf(posts.last())
        val filters = SearchVisibilityFilters(hideLiked = true)
        val state = snapshot(creator = creator(), results = posts)

        val effect = state.reduce(
            CreatorAction.OpenResult(
                index = 0,
                scrollOffsetHint = 25,
                visibleResults = visiblePosts,
                visibilityFilters = filters,
            )
        ).effect
            as CreatorEffect.OpenViewer

        assertEquals(visiblePosts, effect.posts)
        assertEquals("creator:PIXIV:artist-id", effect.context.queryHash)
        assertEquals(0, effect.context.startIndex)
        assertEquals(25, effect.context.scrollOffsetHint)
        assertEquals(ViewerStreamSource.CREATOR_PROFILE, effect.context.streamSource)
        assertEquals(filters, effect.visibilityFilters)
    }

    private fun snapshot(
        creator: CreatorProfile,
        results: List<com.theoriacodex.domain.model.Post> = emptyList(),
        canLoadMore: Boolean = false,
        errorMessage: String? = null,
    ): CreatorUiState {
        return coordinatorSnapshot(
            creator = creator,
            results = results,
            canLoadMore = canLoadMore,
            errorMessage = errorMessage,
        ).toUiState()
    }

    private fun coordinatorSnapshot(
        creator: CreatorProfile,
        results: List<com.theoriacodex.domain.model.Post> = emptyList(),
        canLoadMore: Boolean = false,
        errorMessage: String? = null,
    ): CreatorCoordinatorSnapshot {
        val key = creator.uploadsQuery ?: creator.profileId ?: creator.profileUrl ?: creator.displayName
        return CreatorCoordinatorSnapshot(
            creator = creator,
            queryHash = "creator:${creator.source.name}:$key",
            results = results,
            loading = false,
            loadingMore = false,
            canLoadMore = canLoadMore,
            errorMessage = errorMessage,
        )
    }

    private fun creator(): CreatorProfile {
        return CreatorProfile(
            source = SourceKey.PIXIV,
            displayName = "Artist",
            profileId = "artist-id",
            profileUrl = "https://example.com/artist",
            uploadsQuery = "artist-id",
        )
    }
}
