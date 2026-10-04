package com.theoriacodex.app.recommend.state

import com.theoriacodex.app.recommend.ForYouContinuation
import com.theoriacodex.app.recommend.ForYouFeedResult
import com.theoriacodex.app.recommend.forYouBaseQuery
import com.theoriacodex.app.recommend.forYouSourceQuery
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.search.SearchVisibilityFilters
import com.theoriacodex.data.repository.ForYouBlacklistEntry
import com.theoriacodex.data.repository.RecommendationProfile
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.SourceRunState
import com.theoriacodex.domain.orchestration.SourceRunStatus
import com.theoriacodex.domain.orchestration.UnifiedSearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForYouStateContractTest {
    @Test
    fun `refresh retains cards while replacing request and continuation`() {
        assertNull(ForYouUiState().emptyReason)
        val state = loadedState(listOf(testPost()), token = "next")
        val transition = state.reduce(ForYouAction.Refresh(shuffle = false))
        assertTrue(transition.state.isRefreshing)
        assertEquals(state.results, transition.state.results)
        assertTrue(transition.state.statuses.isEmpty())
        assertFalse(transition.state.canLoadMore)
        assertEquals(ForYouEffect.RefreshFeed(requireNotNull(transition.state.activeRequest), false), transition.effect)
    }

    @Test
    fun `profile source and sort select distinct immutable request identities`() {
        val base = loadedState().copy(
            profiles = listOf(RecommendationProfile("main", "Main"), RecommendationProfile("alt", "Alt")),
            activeProfileId = "main",
            availableSources = listOf(SourceKey.PIXIV, SourceKey.GELBOORU),
        )
        val profile = base.reduce(ForYouAction.SelectProfile("alt"))
        val source = base.reduce(ForYouAction.SelectSource(SourceKey.GELBOORU))
        val sort = base.reduce(ForYouAction.SelectSort(SortMode.TOP))
        assertEquals("alt", profile.state.activeRequest?.profileId)
        assertEquals(SourceKey.GELBOORU, source.state.activeRequest?.source)
        assertEquals(SortMode.TOP, sort.state.activeRequest?.sortMode)
        assertTrue(profile.effect is ForYouEffect.ChangeProfile)
        assertTrue(source.effect is ForYouEffect.ChangeSource)
        assertTrue(sort.effect is ForYouEffect.ChangeSort)
    }

    @Test
    fun `historical replay preserves seed order and can page without current likes`() {
        val seed = linkedMapOf(SourceKey.PIXIV to listOf("pixiv seed"), SourceKey.GELBOORU to listOf("gelbooru seed"))
        val replay = ForYouUiState().reduce(ForYouAction.ReplaySearch(seed, SortMode.TOP))
        assertEquals(seed.keys.toList(), replay.state.seedSummaryBySource.keys.toList())
        assertEquals(SortMode.TOP, replay.state.sortMode)
        assertEquals(seed, (replay.effect as ForYouEffect.ReplaySearch).seedBySource)
        val historical = loadedState(listOf(testPost()), "next", likesCount = 0)
        assertTrue(historical.reduce(ForYouAction.LoadNextPage).state.isPaging)
        assertNull(historical.emptyReason)
    }

    @Test
    fun `root failure exhausted seeds unavailable sources and empty replay have distinct states`() {
        val base = ForYouUiState(activeProfileLikesCount = 2).reduce(ForYouAction.Refresh()).state
        val request = requireNotNull(base.activeRequest)
        val failed = base.reduce(ForYouAction.RefreshFailed(request, "failed")).state
        val noSources = base.reduce(ForYouAction.RefreshCompleted(request, ForYouFeedResult.empty("empty-enabled", 2))).state
        val exhausted = base.reduce(ForYouAction.RefreshCompleted(request, ForYouFeedResult.empty("empty-seed", 2))).state
        assertNull(failed.emptyReason)
        assertEquals("failed", failed.errorMessage)
        assertEquals(ForYouEmptyReason.NO_ENABLED_SOURCES, noSources.emptyReason)
        assertEquals(ForYouEmptyReason.SEEDS_EXHAUSTED, exhausted.emptyReason)
        assertEquals(ForYouEmptyReason.NO_RESULTS, loadedState(likesCount = 0).emptyReason)
    }

    @Test
    fun `page settlement preserves failed tokens and untouched statuses while deduplicating hydration`() {
        val sparse = testPost(sourcePostId = "old", title = null)
        val hydrated = sparse.copy(title = "resolved")
        val base = loadedState(listOf(sparse), "next")
        val mixed = base.copy(
            continuation = requireNotNull(base.continuation).copy(pageTokens = mapOf(SourceKey.PIXIV to "next", SourceKey.GELBOORU to "g2")),
            statuses = listOf(success(SourceKey.PIXIV), success(SourceKey.GELBOORU)),
        )
        val started = mixed.reduce(ForYouAction.LoadNextPage).state
        val request = requireNotNull(started.activeRequest)
        val failed = SourceRunStatus(SourceKey.GELBOORU, SourceRunState.FAILED, errorMessage = "failed")
        val settled = started.reduce(ForYouAction.PageLoaded(request,
            UnifiedSearchResult(listOf(hydrated), mapOf(SourceKey.PIXIV to null), listOf(success(SourceKey.PIXIV), failed)),
            setOf(SourceKey.PIXIV, SourceKey.GELBOORU),
        )).state
        assertEquals(listOf(hydrated), settled.results)
        assertEquals(mapOf(SourceKey.GELBOORU to "g2"), settled.continuation?.pageTokens)
        assertFalse(settled.canLoadMore)
        val retry = settled.reduce(ForYouAction.RetrySource(SourceKey.GELBOORU))
        val effect = retry.effect as ForYouEffect.LoadNextPage
        assertEquals(setOf(SourceKey.GELBOORU), effect.sources)
        assertEquals("g2", effect.continuation.pageTokens[SourceKey.GELBOORU])
        val completed = retry.state.reduce(ForYouAction.PageLoaded(effect.request,
            UnifiedSearchResult(listOf(testPost(source = SourceKey.GELBOORU)), mapOf(SourceKey.GELBOORU to null), listOf(success(SourceKey.GELBOORU))),
            effect.sources,
        )).state
        assertEquals(2, completed.results.size)
        assertEquals(listOf(SourceKey.GELBOORU, SourceKey.PIXIV), completed.statuses.map { it.source })
        assertTrue(completed.statuses.all { it.state == SourceRunState.SUCCESS })
        assertTrue(completed.continuation?.pageTokens.orEmpty().isEmpty())
    }

    @Test
    fun `new generation rejects stale root page failure and cancellation`() {
        val first = loadedState(listOf(testPost()), "next").reduce(ForYouAction.LoadNextPage).state
        val old = requireNotNull(first.activeRequest)
        val second = first.reduce(ForYouAction.ReplaySearch(mapOf(SourceKey.GELBOORU to listOf("new")), SortMode.TOP)).state
        assertEquals(second, second.reduce(ForYouAction.PageLoaded(old, UnifiedSearchResult(emptyList(), emptyMap(), emptyList()), setOf(SourceKey.PIXIV))).state)
        assertEquals(second, second.reduce(ForYouAction.RefreshFailed(old, "stale")).state)
        assertEquals(second, second.reduce(ForYouAction.RequestCancelled(old)).state)
        assertTrue(requireNotNull(second.activeRequest).generation > old.generation)
        val cancelled = second.reduce(ForYouAction.RequestCancelled(requireNotNull(second.activeRequest))).state
        assertFalse(cancelled.isRefreshing)
        assertNull(cancelled.errorMessage)
    }

    @Test
    fun `seed Undo captures exact profile and entries`() {
        val entries = listOf(ForYouBlacklistEntry(SourceKey.PIXIV, listOf("night", "sky")))
        val transition = loadedState().reduce(ForYouAction.UndoSeedBlacklist("profile-main", entries))
        val effect = transition.effect as ForYouEffect.UndoSeedBlacklist
        assertEquals("profile-main", effect.profileId)
        assertEquals(entries, effect.entries)
    }

    @Test
    fun `Viewer effect preserves visible order and filters`() {
        val posts = listOf(testPost(sourcePostId = "1"), testPost(sourcePostId = "2"))
        val filters = SearchVisibilityFilters(hideLiked = true)
        val effect = loadedState(posts).reduce(ForYouAction.OpenResult(0, 25, listOf(posts.last()), filters)).effect as ForYouEffect.OpenViewer
        assertEquals(listOf(posts.last()), effect.posts)
        assertEquals(25, effect.context.scrollOffsetHint)
        assertEquals(ViewerStreamSource.FOR_YOU, effect.context.streamSource)
        assertEquals(filters, effect.visibilityFilters)
    }

    private fun success(source: SourceKey) = SourceRunStatus(source, SourceRunState.SUCCESS)

    private fun loadedState(posts: List<Post> = emptyList(), token: String? = null, likesCount: Int = 2): ForYouUiState {
        val base = ForYouUiState(activeProfileLikesCount = 2).reduce(ForYouAction.Refresh()).state
        val seed = mapOf(SourceKey.PIXIV to listOf("seed"))
        val page = UnifiedSearchResult(posts, mapOf(SourceKey.PIXIV to token), listOf(success(SourceKey.PIXIV)))
        val continuation = ForYouContinuation(forYouBaseQuery(SortMode.NEWEST),
            mapOf(SourceKey.PIXIV to forYouSourceQuery(SourceKey.PIXIV, listOf("seed"), SortMode.NEWEST)),
            emptyMap(), mapOf(SourceKey.PIXIV to null)).accept(page, seed.keys)
        return base.reduce(ForYouAction.RefreshCompleted(requireNotNull(base.activeRequest),
            ForYouFeedResult(seed, "PIXIV:seed", likesCount, page, continuation),
        )).state
    }
}
