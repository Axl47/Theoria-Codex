package com.theoriacodex.app.recommend

import androidx.lifecycle.SavedStateHandle
import com.theoriacodex.app.recommend.state.ForYouAction
import com.theoriacodex.app.recommend.state.ForYouEmptyReason
import com.theoriacodex.domain.orchestration.SourceRunState
import com.theoriacodex.domain.orchestration.SourceRunStatus
import com.theoriacodex.domain.orchestration.UnifiedSearchResult
import com.theoriacodex.app.recommend.state.ForYouEffect
import com.theoriacodex.app.related.LikeToggleOutcome
import com.theoriacodex.app.related.RelatedPostsLoading
import com.theoriacodex.app.related.RelatedPostsUiState
import com.theoriacodex.app.related.loadedPosts
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.ForYouBlacklistEntry
import com.theoriacodex.data.repository.RecommendationProfile
import com.theoriacodex.data.repository.defaultRecommendationProfiles
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ForYouViewModelTest {
    @Test
    fun `saved controls and durable sort reconstruct without provider work`() = runTest {
        val engine = FakeForYouRouteEngine()
        val saved = SavedStateHandle(mapOf(
            ForYouViewModel.KEY_PROFILE_ID to "profile-main",
            ForYouViewModel.KEY_SELECTED_SOURCE to SourceKey.PIXIV.name,
            ForYouViewModel.KEY_SORT_MODE to SortMode.TOP.name,
        ))
        val owner = ForYouViewModel(engine, saved, coroutineScope = this)
        val durable = ForYouViewModel(engine, SavedStateHandle(), initialSort = SortMode.RANDOM, coroutineScope = this)
        assertTrue(engine.requests.isEmpty())
        assertEquals(SourceKey.PIXIV, owner.state.value.selectedSource)
        assertEquals(SortMode.TOP, owner.state.value.sortMode)
        assertEquals(SortMode.RANDOM, durable.state.value.sortMode)
    }

    @Test
    fun `environment starts one root and count only updates including zero preserve it`() = runTest {
        val first = testPost(sourcePostId = "first")
        val engine = FakeForYouRouteEngine().apply { refreshResult = listOf(first) }
        val owner = ForYouViewModel(engine, SavedStateHandle(), coroutineScope = this)
        owner.synchronizeEnvironment(AppSettings(), 1)
        advanceUntilIdle()
        owner.synchronizeEnvironment(AppSettings(), 2)
        owner.synchronizeEnvironment(AppSettings(), 0)
        owner.onSourceAvailabilityChanged()
        advanceUntilIdle()
        assertEquals(1, engine.requests.size)
        assertEquals(listOf(first), owner.state.value.results)
        assertEquals(0, owner.state.value.activeProfileLikesCount)
        assertEquals(1, engine.records.size)
        val effect = async { owner.effects.first() }
        owner.onAction(ForYouAction.OpenResult(0, 24))
        val open = effect.await() as ForYouEffect.OpenViewer
        assertEquals("for_you:PIXIV:seed", open.context.queryHash)
        assertEquals(24, open.context.scrollOffsetHint)
    }

    @Test
    fun `cold replay waits for both startup inputs in either order and survives zero likes updates`() = runTest {
        for (sourceFirst in listOf(true, false)) {
            val engine = FakeForYouRouteEngine().apply { refreshResult = listOf(testPost()) }
            val owner = ForYouViewModel(engine, SavedStateHandle(), coroutineScope = this)
            val seed = linkedMapOf(SourceKey.GELBOORU to listOf("saved gelbooru"), SourceKey.PIXIV to listOf("saved pixiv"))
            owner.onAction(ForYouAction.ReplaySearch(seed, SortMode.TOP))
            if (sourceFirst) owner.onSourceAvailabilityChanged() else owner.synchronizeEnvironment(AppSettings(), 0)
            runCurrent()
            assertTrue(engine.requests.isEmpty())
            if (sourceFirst) owner.synchronizeEnvironment(AppSettings(), 0) else owner.onSourceAvailabilityChanged()
            advanceUntilIdle()
            owner.synchronizeEnvironment(AppSettings(), 0)
            advanceUntilIdle()
            assertEquals(seed, engine.requests.single().historicalSeed)
            assertEquals(SortMode.TOP, engine.requests.single().sortMode)
            assertEquals(seed, owner.state.value.seedSummaryBySource)
            assertNull(owner.state.value.emptyReason)
        }
    }

    @Test
    fun `new empty route without likes shows training state and starts no request`() = runTest {
        val engine = FakeForYouRouteEngine()
        val owner = ForYouViewModel(engine, SavedStateHandle(), coroutineScope = this)
        owner.synchronizeEnvironment(AppSettings(), 0)
        owner.onSourceAvailabilityChanged()
        advanceUntilIdle()
        assertEquals(ForYouEmptyReason.NO_LIKES, owner.state.value.emptyReason)
        assertTrue(engine.requests.isEmpty())
    }

    @Test
    fun `root completion cannot overwrite a newer Likes projection`() = runTest {
        val release = CompletableDeferred<Unit>()
        val engine = FakeForYouRouteEngine().apply {
            firstStarted = CompletableDeferred()
            firstRelease = release
        }
        val owner = ForYouViewModel(engine, SavedStateHandle(), coroutineScope = this)
        owner.synchronizeEnvironment(AppSettings(), 1)
        runCurrent()
        owner.synchronizeEnvironment(AppSettings(), 0)
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(0, owner.state.value.activeProfileLikesCount)
        assertEquals(1, engine.requests.size)
    }

    @Test
    fun `source and sort controls capture immutable requests and persist inputs`() = runTest {
        val engine = FakeForYouRouteEngine()
        val saved = SavedStateHandle()
        val owner = ForYouViewModel(engine, saved, coroutineScope = this)
        owner.synchronizeEnvironment(AppSettings(), 1)
        advanceUntilIdle()
        owner.onAction(ForYouAction.SelectSort(SortMode.TOP))
        advanceUntilIdle()
        owner.onAction(ForYouAction.SelectSource(SourceKey.GELBOORU))
        advanceUntilIdle()
        assertEquals(SortMode.TOP, engine.requests.last().sortMode)
        assertEquals(SourceKey.GELBOORU, engine.requests.last().selectedSource)
        assertEquals(SortMode.TOP.name, saved.get<String>(ForYouViewModel.KEY_SORT_MODE))
        assertEquals(SourceKey.GELBOORU.name, saved.get<String>(ForYouViewModel.KEY_SELECTED_SOURCE))
    }

    @Test
    fun `settings change cancels stale root and rejects late completion and history`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val engine = FakeForYouRouteEngine().apply {
            firstStarted = started
            firstRelease = release
            refreshResult = listOf(testPost(sourcePostId = "fresh"))
        }
        val owner = ForYouViewModel(engine, SavedStateHandle(), coroutineScope = this)
        owner.synchronizeEnvironment(AppSettings(), 1)
        runCurrent()
        started.await()
        val changed = AppSettings().let { it.copy(runtime = it.runtime.copy(sourceWeights = mapOf(SourceKey.PIXIV to 0.25))) }
        owner.synchronizeEnvironment(changed, 1)
        runCurrent()
        assertEquals(listOf("fresh"), owner.state.value.results.map { it.id.sourcePostId })
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("fresh"), owner.state.value.results.map { it.id.sourcePostId })
        assertEquals(1, engine.records.size)
    }

    @Test
    fun `page exception retains token and explicit retry preserves results without another root`() = runTest {
        val first = testPost(sourcePostId = "first")
        val engine = FakeForYouRouteEngine().apply {
            refreshResult = listOf(first)
            refreshCanLoadMore = true
            pageError = "page unavailable"
        }
        val owner = ForYouViewModel(engine, SavedStateHandle(), coroutineScope = this)
        owner.synchronizeEnvironment(AppSettings(), 1)
        advanceUntilIdle()
        owner.onAction(ForYouAction.LoadNextPage)
        advanceUntilIdle()
        assertEquals(listOf(first), owner.state.value.results)
        assertFalse(owner.state.value.isPaging)
        assertEquals("page unavailable", owner.state.value.statuses.single().errorMessage)
        assertEquals("next", owner.state.value.continuation?.pageTokens?.get(SourceKey.PIXIV))
        engine.pageError = null
        engine.pageResult = listOf(first, testPost(sourcePostId = "second"))
        owner.onAction(ForYouAction.RetrySource(SourceKey.PIXIV))
        advanceUntilIdle()
        assertEquals(listOf("first", "second"), owner.state.value.results.map { it.id.sourcePostId })
        assertEquals(listOf("next", "next"), engine.pageTokens)
        assertEquals(1, engine.records.size)
    }

    @Test
    fun `committed related likes promote once and survive pagination before root replacement`() = runTest {
        val seed = testPost(sourcePostId = "seed")
        val promoted = testPost(sourcePostId = "promoted")
        val sibling = testPost(sourcePostId = "sibling")
        val next = testPost(sourcePostId = "next")
        val engine = FakeForYouRouteEngine().apply { refreshResult = listOf(seed); refreshCanLoadMore = true }
        val loader = FakeForYouRelatedPostsLoader { id -> if (id == seed.id) listOf(promoted, sibling) else listOf(next) }
        val owner = ForYouViewModel(engine, SavedStateHandle(), coroutineScope = this, relatedPostsLoader = loader)
        owner.synchronizeEnvironment(AppSettings(), 1)
        advanceUntilIdle()
        owner.onAction(ForYouAction.RelatedLikeCommitted(seed, LikeToggleOutcome.LIKED))
        advanceUntilIdle()
        owner.onAction(ForYouAction.RelatedLikeCommitted(promoted, LikeToggleOutcome.LIKED))
        advanceUntilIdle()
        owner.onAction(ForYouAction.ShowPreviousRelatedPosts)
        assertEquals(listOf(sibling), owner.state.value.relatedPosts.loadedPosts)
        owner.onAction(ForYouAction.ShowNextRelatedPosts)
        assertEquals(listOf(next), owner.state.value.relatedPosts.loadedPosts)
        val effect = async { owner.effects.first() }
        owner.onAction(ForYouAction.OpenRelatedResult(0, listOf(next)))
        val open = effect.await() as ForYouEffect.OpenViewer
        assertEquals(ViewerStreamSource.RELATED, open.context.streamSource)
        assertFalse(open.liveSearchBinding)
        engine.pageResult = listOf(promoted, testPost(sourcePostId = "page"))
        owner.onAction(ForYouAction.LoadNextPage)
        advanceUntilIdle()
        owner.synchronizeEnvironment(AppSettings(), 3)
        assertEquals(listOf("seed", "promoted", "page"), owner.state.value.results.map { it.id.sourcePostId })
        engine.refreshResult = emptyList()
        owner.onAction(ForYouAction.Refresh())
        advanceUntilIdle()
        assertTrue(owner.state.value.results.isEmpty())
        assertEquals(RelatedPostsUiState.Idle, owner.state.value.relatedPosts)
    }

    @Test
    fun `own blacklist settings emission cannot cancel feedback or start a duplicate root`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val engine = FakeForYouRouteEngine().apply { blacklistStarted = started; blacklistRelease = release }
        val owner = ForYouViewModel(engine, SavedStateHandle(), coroutineScope = this)
        owner.synchronizeEnvironment(AppSettings(), 1)
        advanceUntilIdle()
        val feedback = async { owner.effects.first() }
        owner.onAction(ForYouAction.BlacklistCurrentSeed)
        runCurrent()
        started.await()
        owner.synchronizeEnvironment(engine.blacklistSettings(), 1)
        release.complete(Unit)
        advanceUntilIdle()
        val hidden = feedback.await() as ForYouEffect.SeedHidden
        assertEquals(engine.blacklistEntries, hidden.entries)
        assertEquals("profile-main", hidden.profileId)
        assertEquals(2, engine.requests.size)
        assertEquals("empty-seed", owner.state.value.seedId)
        owner.onAction(ForYouAction.UndoSeedBlacklist(hidden.profileId, hidden.entries))
        advanceUntilIdle()
        assertEquals(hidden.profileId to hidden.entries, engine.undoneBlacklist)
        assertEquals(3, engine.requests.size)
    }

    @Test
    fun `already hidden seed reports its result without inventing an Undo payload`() = runTest {
        val engine = FakeForYouRouteEngine().apply { blacklistEntries = emptyList() }
        val owner = ForYouViewModel(engine, SavedStateHandle(), coroutineScope = this)
        owner.synchronizeEnvironment(AppSettings(), 1)
        advanceUntilIdle()
        val feedback = async { owner.effects.first() }
        owner.onAction(ForYouAction.BlacklistCurrentSeed)
        advanceUntilIdle()
        assertTrue((feedback.await() as ForYouEffect.ShowMessage).message.contains("already hidden"))
    }
}

private class FakeForYouRouteEngine : ForYouRouteEngine {
    val requests = mutableListOf<ForYouFeedRequest>()
    val records = mutableListOf<ForYouFeedResult>()
    val pageTokens = mutableListOf<String?>()
    var refreshResult = listOf(testPost(sourcePostId = "first"))
    var refreshCanLoadMore = false
    var pageResult: List<Post> = emptyList()
    var pageError: String? = null
    var firstStarted: CompletableDeferred<Unit>? = null
    var firstRelease: CompletableDeferred<Unit>? = null
    var blacklistStarted: CompletableDeferred<Unit>? = null
    var blacklistRelease: CompletableDeferred<Unit>? = null
    var blacklistEntries = listOf(ForYouBlacklistEntry(SourceKey.PIXIV, listOf("seed")))
    var undoneBlacklist: Pair<String, List<ForYouBlacklistEntry>>? = null
    private var hidden = false

    override fun availableSources(settings: AppSettings) = listOf(SourceKey.PIXIV, SourceKey.GELBOORU)

    override suspend fun execute(request: ForYouFeedRequest): ForYouFeedResult {
        requests += request
        val started = firstStarted
        val release = firstRelease
        val stale = requests.size == 1 && started != null && release != null
        if (stale) withContext(NonCancellable) { started.complete(Unit); release.await() }
        if (hidden) return ForYouFeedResult.empty("empty-seed", request.likesCount)
        val seed = request.historicalSeed ?: mapOf((request.selectedSource ?: SourceKey.PIXIV) to listOf("seed"))
        val posts = if (stale) listOf(testPost(sourcePostId = "stale")) else refreshResult
        val page = UnifiedSearchResult(posts, seed.keys.associateWith { if (refreshCanLoadMore) "next" else null },
            seed.keys.map { SourceRunStatus(it, SourceRunState.SUCCESS) })
        val continuation = ForYouContinuation(forYouBaseQuery(request.sortMode),
            seed.mapValues { (source, tags) -> forYouSourceQuery(source, tags, request.sortMode) }, emptyMap(),
            seed.keys.associateWith { null }).accept(page, seed.keys)
        return ForYouFeedResult(seed, buildForYouSeedId(seed), request.likesCount, page, continuation)
    }

    override suspend fun loadPage(continuation: ForYouContinuation, sources: Set<SourceKey>): UnifiedSearchResult {
        pageTokens += sources.map { continuation.pageTokens[it] }
        pageError?.let { error(it) }
        return UnifiedSearchResult(pageResult, sources.associateWith { null }, sources.map { SourceRunStatus(it, SourceRunState.SUCCESS) })
    }

    fun blacklistSettings() = AppSettings().copy(forYouBlacklistByProfile = mapOf("profile-main" to blacklistEntries))

    override suspend fun updateBlacklist(profileId: String, entries: List<ForYouBlacklistEntry>, hidden: Boolean): ForYouBlacklistChange {
        blacklistStarted?.complete(Unit)
        blacklistRelease?.await()
        this.hidden = hidden
        if (!hidden) undoneBlacklist = profileId to entries
        return ForYouBlacklistChange(if (hidden) blacklistSettings() else AppSettings(), blacklistEntries)
    }

    override suspend fun recordAccepted(result: ForYouFeedResult) { records += result }
    override suspend fun resolvePost(postId: PostId): Post? = null
}

private class FakeForYouRelatedPostsLoader(
    private val resultForSeed: (PostId) -> List<Post>,
) : RelatedPostsLoading {
    constructor(posts: List<Post>) : this({ posts })

    val requests = mutableListOf<PostId>()
    override fun supports(source: SourceKey): Boolean = source == SourceKey.PIXIV
    override suspend fun load(seed: PostId): List<Post> {
        requests += seed
        return resultForSeed(seed)
    }
}
