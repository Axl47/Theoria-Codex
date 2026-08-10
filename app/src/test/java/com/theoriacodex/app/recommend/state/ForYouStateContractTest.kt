package com.theoriacodex.app.recommend.state

import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.search.SearchVisibilityFilters
import com.theoriacodex.data.repository.ForYouBlacklistEntry
import com.theoriacodex.data.repository.RecommendationProfile
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.SourceRunState
import com.theoriacodex.domain.orchestration.SourceRunStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForYouStateContractTest {
    @Test
    fun `initial snapshot remains neutral and refresh transition preserves results`() {
        val initial = snapshot(seedId = "init").toUiState()
        assertNull(initial.emptyReason)
        assertFalse(initial.isRefreshing)

        val existing = testPost(sourcePostId = "existing")
        val state = snapshot(
            likesCount = 3,
            seedId = "PIXIV:sky",
            results = listOf(existing),
            statuses = listOf(SourceRunStatus(SourceKey.PIXIV, SourceRunState.SUCCESS)),
            canLoadMore = true,
        ).toUiState()

        val transition = state.reduce(ForYouAction.Refresh(shuffle = false))

        assertTrue(transition.state.isRefreshing)
        assertEquals(listOf(existing), transition.state.results)
        assertTrue(transition.state.statuses.isEmpty())
        assertFalse(transition.state.canLoadMore)
        assertEquals(
            ForYouEffect.RefreshFeed(
                request = requireNotNull(transition.state.activeRequest),
                shuffle = false,
            ),
            transition.effect,
        )
    }

    @Test
    fun `profile source and sort selections are typed deterministic transitions`() {
        val profiles = listOf(
            RecommendationProfile("main", "Main"),
            RecommendationProfile("alt", "Alt"),
        )
        val base = snapshot(
            profiles = profiles,
            activeProfileId = "main",
            likesCount = 2,
            availableSources = listOf(SourceKey.PIXIV, SourceKey.GELBOORU),
            selectedSource = SourceKey.PIXIV,
            seedId = "PIXIV:sky",
        ).toUiState()

        val profile = base.reduce(ForYouAction.SelectProfile("alt"))
        val source = base.reduce(ForYouAction.SelectSource(SourceKey.GELBOORU))
        val sort = base.reduce(ForYouAction.SelectSort(SortMode.TOP))

        assertEquals("alt", profile.state.activeProfileId)
        assertEquals(
            ForYouEffect.ChangeProfile(requireNotNull(profile.state.activeRequest), "alt"),
            profile.effect,
        )
        assertEquals(SourceKey.GELBOORU, source.state.selectedSource)
        assertEquals(
            ForYouEffect.ChangeSource(requireNotNull(source.state.activeRequest), SourceKey.GELBOORU),
            source.effect,
        )
        assertEquals(SortMode.TOP, sort.state.sortMode)
        assertEquals(
            ForYouEffect.ChangeSort(requireNotNull(sort.state.activeRequest), SortMode.TOP),
            sort.effect,
        )
    }

    @Test
    fun `historical search starts a refresh with exact source tags and sort`() {
        val state = snapshot(
            likesCount = 2,
            availableSources = listOf(SourceKey.PIXIV, SourceKey.GELBOORU),
        ).toUiState()
        val seed = linkedMapOf(
            SourceKey.PIXIV to listOf("pixiv seed"),
            SourceKey.GELBOORU to listOf("gelbooru seed"),
        )

        val transition = state.reduce(ForYouAction.ReplaySearch(seed, SortMode.TOP))

        assertTrue(transition.state.isRefreshing)
        assertEquals(SortMode.TOP, transition.state.sortMode)
        assertNull(transition.state.selectedSource)
        assertEquals(seed, transition.state.seedSummaryBySource)
        assertEquals("GELBOORU:gelbooru seed|PIXIV:pixiv seed", transition.state.seedId)
        assertEquals(
            ForYouEffect.ReplaySearch(
                request = requireNotNull(transition.state.activeRequest),
                seedBySource = seed,
                sortMode = SortMode.TOP,
            ),
            transition.effect,
        )
    }

    @Test
    fun `empty error and blacklist exhausted snapshots stay distinguishable`() {
        val noSources = snapshot(seedId = "empty-enabled", likesCount = 2).toUiState()
        val exhausted = snapshot(
            seedId = "empty-seed",
            likesCount = 2,
            blacklist = listOf(ForYouBlacklistEntry(SourceKey.PIXIV, listOf("sky"))),
        ).toUiState()
        val failed = snapshot(
            seedId = "empty-seed",
            likesCount = 2,
            errorMessage = "provider failed",
        ).toUiState()

        assertEquals(ForYouEmptyReason.NO_ENABLED_SOURCES, noSources.emptyReason)
        assertEquals(ForYouEmptyReason.SEEDS_EXHAUSTED, exhausted.emptyReason)
        assertEquals(1, exhausted.blacklistEntries.size)
        assertNull(failed.emptyReason)
        assertEquals("provider failed", failed.errorMessage)
    }

    @Test
    fun `seed blacklist undo carries the exact profile and entries into refresh`() {
        val entry = ForYouBlacklistEntry(SourceKey.PIXIV, listOf("sky", "night"))
        val state = snapshot(likesCount = 2, seedId = "PIXIV:sky+night").toUiState()

        val transition = state.reduce(
            ForYouAction.UndoSeedBlacklist(
                profileId = "profile-main",
                entries = listOf(entry),
            )
        )

        assertTrue(transition.state.isRefreshing)
        assertEquals(
            ForYouEffect.UndoSeedBlacklist(
                request = requireNotNull(transition.state.activeRequest),
                profileId = "profile-main",
                entries = listOf(entry),
            ),
            transition.effect,
        )
    }

    @Test
    fun `paging transitions merge identities and retain incoming hydration`() {
        val sparse = testPost(sourcePostId = "1", title = null)
        val hydrated = sparse.copy(title = "Hydrated")
        val next = testPost(sourcePostId = "2")
        val state = snapshot(
            likesCount = 2,
            seedId = "PIXIV:sky",
            results = listOf(sparse),
            canLoadMore = true,
        ).toUiState()

        val started = state.reduce(ForYouAction.LoadNextPage)
        val request = requireNotNull(started.state.activeRequest)
        val completed = started.state.reduce(
            ForYouAction.PageLoaded(
                request = request,
                posts = listOf(hydrated, next),
                statuses = listOf(SourceRunStatus(SourceKey.PIXIV, SourceRunState.SUCCESS)),
                canLoadMore = false,
            )
        )

        assertTrue(started.state.isPaging)
        assertEquals(ForYouEffect.LoadNextPage(request), started.effect)
        assertEquals(listOf(hydrated, next), completed.state.results)
        assertFalse(completed.state.isPaging)
        assertFalse(completed.state.canLoadMore)
        assertNull(completed.state.activeRequest)
    }

    @Test
    fun `new profile generation rejects stale root completion failure and cancellation`() {
        val profiles = listOf(
            RecommendationProfile("main", "Main"),
            RecommendationProfile("alt", "Alt"),
        )
        val base = snapshot(
            profiles = profiles,
            activeProfileId = "main",
            likesCount = 2,
            seedId = "PIXIV:old",
        ).toUiState()
        val first = base.reduce(ForYouAction.Refresh(shuffle = false))
        val firstRequest = requireNotNull(first.state.activeRequest)
        val second = first.state.reduce(ForYouAction.SelectProfile("alt"))
        val secondRequest = requireNotNull(second.state.activeRequest)
        val staleSnapshot = snapshot(
            profiles = profiles,
            activeProfileId = "main",
            likesCount = 2,
            seedId = "PIXIV:stale",
            results = listOf(testPost(sourcePostId = "stale")),
        )

        val afterStaleCompletion = second.state.reduce(
            ForYouAction.RefreshCompleted(firstRequest, staleSnapshot)
        ).state
        val afterStaleFailure = afterStaleCompletion.reduce(
            ForYouAction.RefreshFailed(firstRequest, "stale failure")
        ).state
        val afterStaleCancellation = afterStaleFailure.reduce(
            ForYouAction.RequestCancelled(firstRequest)
        ).state

        assertEquals(second.state, afterStaleCompletion)
        assertEquals(second.state, afterStaleFailure)
        assertEquals(second.state, afterStaleCancellation)
        assertTrue(secondRequest.generation > firstRequest.generation)

        val currentSnapshot = snapshot(
            profiles = profiles,
            activeProfileId = "alt",
            likesCount = 4,
            seedId = "PIXIV:current",
            results = listOf(testPost(sourcePostId = "current")),
        )
        val completed = afterStaleCancellation.reduce(
            ForYouAction.RefreshCompleted(secondRequest, currentSnapshot)
        ).state

        assertEquals("alt", completed.activeProfileId)
        assertEquals("current", completed.results.single().id.sourcePostId)
        assertEquals(second.state.nextRequestGeneration, completed.nextRequestGeneration)
        assertNull(completed.activeRequest)
    }

    @Test
    fun `cancelled page can be retried and stale page result cannot win`() {
        val state = snapshot(
            likesCount = 2,
            seedId = "PIXIV:sky",
            results = listOf(testPost(sourcePostId = "existing")),
            canLoadMore = true,
        ).toUiState()
        val first = state.reduce(ForYouAction.LoadNextPage)
        val firstRequest = requireNotNull(first.state.activeRequest)
        val cancelled = first.state.reduce(ForYouAction.RequestCancelled(firstRequest)).state
        val second = cancelled.reduce(ForYouAction.LoadNextPage)
        val secondRequest = requireNotNull(second.state.activeRequest)

        val afterStale = second.state.reduce(
            ForYouAction.PageLoaded(
                request = firstRequest,
                posts = listOf(testPost(sourcePostId = "stale")),
                statuses = emptyList(),
                canLoadMore = false,
            )
        ).state
        val afterStaleFailure = afterStale.reduce(
            ForYouAction.PageFailed(firstRequest, "stale page failure")
        ).state

        assertEquals(second.state, afterStale)
        assertEquals(second.state, afterStaleFailure)
        assertTrue(secondRequest.generation > firstRequest.generation)

        val completed = afterStaleFailure.reduce(
            ForYouAction.PageLoaded(
                request = secondRequest,
                posts = listOf(testPost(sourcePostId = "current")),
                statuses = emptyList(),
                canLoadMore = false,
            )
        ).state
        assertEquals(listOf("existing", "current"), completed.results.map { it.id.sourcePostId })
        assertNull(completed.activeRequest)
    }

    @Test
    fun `open result produces a pure viewer launch intent`() {
        val posts = listOf(testPost(sourcePostId = "1"), testPost(sourcePostId = "2"))
        val visiblePosts = listOf(posts.last())
        val filters = SearchVisibilityFilters(animatedOnly = true)
        val state = snapshot(
            likesCount = 2,
            seedId = "PIXIV:sky",
            results = posts,
        ).toUiState()

        val effect = state.reduce(
            ForYouAction.OpenResult(
                index = 0,
                scrollOffsetHint = 80,
                visibleResults = visiblePosts,
                visibilityFilters = filters,
            )
        ).effect
            as ForYouEffect.OpenViewer

        assertEquals(visiblePosts, effect.posts)
        assertEquals("for_you:PIXIV:sky", effect.context.queryHash)
        assertEquals(0, effect.context.startIndex)
        assertEquals(80, effect.context.scrollOffsetHint)
        assertEquals(ViewerStreamSource.FOR_YOU, effect.context.streamSource)
        assertEquals(filters, effect.visibilityFilters)
    }

    private fun snapshot(
        profiles: List<RecommendationProfile> = listOf(RecommendationProfile("main", "Main")),
        activeProfileId: String = "main",
        likesCount: Int = 0,
        availableSources: List<SourceKey> = listOf(SourceKey.PIXIV),
        selectedSource: SourceKey? = null,
        seedId: String = "init",
        results: List<com.theoriacodex.domain.model.Post> = emptyList(),
        statuses: List<SourceRunStatus> = emptyList(),
        blacklist: List<ForYouBlacklistEntry> = emptyList(),
        canLoadMore: Boolean = false,
        errorMessage: String? = null,
    ): ForYouCoordinatorSnapshot {
        return ForYouCoordinatorSnapshot(
            profiles = profiles,
            activeProfileId = activeProfileId,
            activeProfileLikesCount = likesCount,
            availableSources = availableSources,
            selectedSource = selectedSource,
            sortMode = SortMode.NEWEST,
            seedId = seedId,
            seedSummaryBySource = if (seedId.startsWith("empty") || seedId == "init") {
                emptyMap()
            } else {
                mapOf(SourceKey.PIXIV to listOf("sky"))
            },
            blacklistEntries = blacklist,
            results = results,
            statuses = statuses,
            loading = false,
            loadingMore = false,
            canLoadMore = canLoadMore,
            errorMessage = errorMessage,
        )
    }
}
