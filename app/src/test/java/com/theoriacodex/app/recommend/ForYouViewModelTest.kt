package com.theoriacodex.app.recommend

import androidx.lifecycle.SavedStateHandle
import com.theoriacodex.app.recommend.state.ForYouAction
import com.theoriacodex.app.recommend.state.ForYouCoordinatorSnapshot
import com.theoriacodex.app.recommend.state.ForYouEffect
import com.theoriacodex.app.testing.TestAnimatedDurationEnricher
import com.theoriacodex.app.testing.animatedTestPost
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.ForYouBlacklistEntry
import com.theoriacodex.data.repository.RecommendationProfile
import com.theoriacodex.data.repository.defaultRecommendationProfiles
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
    fun `saved route inputs reconstruct before provider work`() = runTest {
        val engine = FakeForYouRouteEngine()
        val savedState = SavedStateHandle(
            mapOf(
                ForYouViewModel.KEY_PROFILE_ID to "profile-main",
                ForYouViewModel.KEY_SELECTED_SOURCE to SourceKey.PIXIV.name,
                ForYouViewModel.KEY_SORT_MODE to SortMode.TOP.name,
            )
        )

        val viewModel = ForYouViewModel(engine, savedState, coroutineScope = this)
        advanceUntilIdle()

        assertEquals(1, engine.initializeCalls)
        assertEquals(SourceKey.PIXIV, engine.restoredSource)
        assertEquals(SortMode.TOP, engine.restoredSort)
        assertEquals(SourceKey.PIXIV, viewModel.state.value.selectedSource)
        assertEquals(SortMode.TOP, viewModel.state.value.sortMode)
    }

    @Test
    fun `environment refresh publishes results and viewer launch as buffered effect`() = runTest {
        val engine = FakeForYouRouteEngine().apply {
            refreshResult = listOf(testPost(sourcePostId = "recommended"))
        }
        val viewModel = ForYouViewModel(engine, SavedStateHandle(), coroutineScope = this)
        advanceUntilIdle()

        viewModel.synchronizeEnvironment(AppSettings(), activeProfileLikesCount = 1)
        advanceUntilIdle()

        assertEquals(listOf("recommended"), viewModel.state.value.results.map { it.id.sourcePostId })
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(ForYouAction.OpenResult(index = 0, scrollOffsetHint = 24))
        val open = effect.await() as ForYouEffect.OpenViewer
        assertEquals("for_you:seed", open.context.queryHash)
        assertEquals(24, open.context.scrollOffsetHint)
    }

    @Test
    fun `cold Recents replay waits for environment and source sync and runs without likes`() = runTest {
        val result = testPost(sourcePostId = "historical")
        val engine = FakeForYouRouteEngine().apply {
            replayResult = listOf(result)
            settingsChangedResult = true
            availabilityChangedResult = true
        }
        val viewModel = ForYouViewModel(engine, SavedStateHandle(), coroutineScope = this)
        val seed = mapOf(SourceKey.PIXIV to listOf("saved seed"))
        advanceUntilIdle()

        viewModel.onAction(ForYouAction.ReplaySearch(seed, SortMode.TOP))
        runCurrent()
        assertNull(engine.replayedSearch)

        viewModel.synchronizeEnvironment(AppSettings(), activeProfileLikesCount = 0)
        runCurrent()
        assertNull(engine.replayedSearch)

        viewModel.onSourceAvailabilityChanged()
        advanceUntilIdle()

        assertEquals(seed to SortMode.TOP, engine.replayedSearch)
        assertEquals(listOf(result), viewModel.state.value.results)
        assertEquals(seed, viewModel.state.value.seedSummaryBySource)
        assertNull(viewModel.state.value.emptyReason)
    }

    @Test
    fun `cold Recents replay also waits when source sync arrives before environment`() = runTest {
        val result = testPost(sourcePostId = "historical")
        val engine = FakeForYouRouteEngine().apply {
            replayResult = listOf(result)
            availabilityChangedResult = true
        }
        val viewModel = ForYouViewModel(engine, SavedStateHandle(), coroutineScope = this)
        val seed = mapOf(SourceKey.GELBOORU to listOf("saved seed"))
        advanceUntilIdle()

        viewModel.onAction(ForYouAction.ReplaySearch(seed, SortMode.POPULAR))
        viewModel.onSourceAvailabilityChanged()
        runCurrent()
        assertNull(engine.replayedSearch)

        viewModel.synchronizeEnvironment(AppSettings(), activeProfileLikesCount = 0)
        advanceUntilIdle()

        assertEquals(seed to SortMode.POPULAR, engine.replayedSearch)
        assertEquals(listOf(result), viewModel.state.value.results)
    }

    @Test
    fun `sort and source actions are owned by route and persisted`() = runTest {
        val engine = FakeForYouRouteEngine(
            availableSources = listOf(SourceKey.PIXIV, SourceKey.GELBOORU),
        ).apply {
            refreshResult = listOf(testPost(sourcePostId = "recommended"))
        }
        val savedState = SavedStateHandle()
        val viewModel = ForYouViewModel(engine, savedState, coroutineScope = this)
        advanceUntilIdle()
        viewModel.synchronizeEnvironment(AppSettings(), activeProfileLikesCount = 1)
        advanceUntilIdle()

        viewModel.onAction(ForYouAction.SelectSort(SortMode.TOP))
        advanceUntilIdle()
        viewModel.onAction(ForYouAction.SelectSource(SourceKey.GELBOORU))
        advanceUntilIdle()

        assertEquals(SortMode.TOP, engine.current.sortMode)
        assertEquals(SourceKey.GELBOORU, engine.current.selectedSource)
        assertEquals(SortMode.TOP.name, savedState.get<String>(ForYouViewModel.KEY_SORT_MODE))
        assertEquals(SourceKey.GELBOORU.name, savedState.get<String>(ForYouViewModel.KEY_SELECTED_SOURCE))
    }

    @Test
    fun `paging failure preserves current results and clears paging owner`() = runTest {
        val first = testPost(sourcePostId = "first")
        val engine = FakeForYouRouteEngine().apply {
            refreshResult = listOf(first)
            refreshCanLoadMore = true
            pageError = "page unavailable"
        }
        val viewModel = ForYouViewModel(engine, SavedStateHandle(), coroutineScope = this)
        advanceUntilIdle()
        viewModel.synchronizeEnvironment(AppSettings(), activeProfileLikesCount = 1)
        advanceUntilIdle()

        viewModel.onAction(ForYouAction.LoadNextPage)
        advanceUntilIdle()

        assertEquals(listOf(first), viewModel.state.value.results)
        assertEquals("page unavailable", viewModel.state.value.errorMessage)
        assertFalse(viewModel.state.value.isPaging)
    }

    @Test
    fun `settings replacement cancels old job and rejects its late completion`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val stale = testPost(sourcePostId = "stale")
        val fresh = testPost(sourcePostId = "fresh")
        val engine = FakeForYouRouteEngine().apply {
            firstRefreshStarted = started
            firstRefreshRelease = release
            firstRefreshResult = listOf(stale)
            refreshResult = listOf(fresh)
        }
        val viewModel = ForYouViewModel(engine, SavedStateHandle(), coroutineScope = this)
        runCurrent()

        viewModel.synchronizeEnvironment(AppSettings(), activeProfileLikesCount = 1)
        runCurrent()
        started.await()

        engine.settingsChangedResult = true
        viewModel.synchronizeEnvironment(AppSettings(), activeProfileLikesCount = 2)
        runCurrent()
        assertEquals(listOf("fresh"), viewModel.state.value.results.map { it.id.sourcePostId })

        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("fresh"), viewModel.state.value.results.map { it.id.sourcePostId })
    }

    @Test
    fun `blacklist exhaustion emits user result and publishes empty seed`() = runTest {
        val engine = FakeForYouRouteEngine().apply {
            val first = testPost(sourcePostId = "first")
            current = current.copy(
                activeProfileLikesCount = 1,
                results = listOf(first),
                seedSummaryBySource = mapOf(SourceKey.PIXIV to listOf("seed")),
                seedId = "seed",
            )
            refreshResult = listOf(first)
            blacklistAdditions = emptyList()
        }
        val viewModel = ForYouViewModel(engine, SavedStateHandle(), coroutineScope = this)
        advanceUntilIdle()
        viewModel.synchronizeEnvironment(AppSettings(), activeProfileLikesCount = 1)
        advanceUntilIdle()

        val effect = async { viewModel.effects.first() }
        viewModel.onAction(ForYouAction.BlacklistCurrentSeed)
        advanceUntilIdle()

        val message = effect.await() as ForYouEffect.ShowMessage
        assertTrue(message.message.contains("already hidden"))
        assertEquals("empty-seed", viewModel.state.value.seedId)
    }

    @Test
    fun `successful seed hide publishes exact undo payload and undo returns it to engine`() = runTest {
        val added = listOf(
            ForYouBlacklistEntry(SourceKey.PIXIV, listOf("seed")),
            ForYouBlacklistEntry(SourceKey.GELBOORU, listOf("night")),
        )
        val engine = FakeForYouRouteEngine().apply {
            current = current.copy(
                activeProfileId = "profile-main",
                activeProfileLikesCount = 1,
                results = listOf(testPost(sourcePostId = "first")),
                seedSummaryBySource = mapOf(SourceKey.PIXIV to listOf("seed")),
                seedId = "seed",
            )
            blacklistAdditions = added
        }
        val viewModel = ForYouViewModel(engine, SavedStateHandle(), coroutineScope = this)
        advanceUntilIdle()
        viewModel.synchronizeEnvironment(AppSettings(), activeProfileLikesCount = 1)
        advanceUntilIdle()

        val effect = async { viewModel.effects.first() }
        viewModel.onAction(ForYouAction.BlacklistCurrentSeed)
        advanceUntilIdle()
        val hidden = effect.await() as ForYouEffect.SeedHidden

        assertEquals("profile-main", hidden.profileId)
        assertEquals(added, hidden.entries)

        viewModel.onAction(ForYouAction.UndoSeedBlacklist(hidden.profileId, hidden.entries))
        advanceUntilIdle()
        assertEquals("profile-main" to added, engine.undoneBlacklist)
    }

    @Test
    fun `settings refresh cannot cancel seed hidden feedback`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val added = listOf(ForYouBlacklistEntry(SourceKey.PIXIV, listOf("seed")))
        val engine = FakeForYouRouteEngine().apply {
            current = current.copy(
                activeProfileLikesCount = 1,
                results = listOf(testPost(sourcePostId = "first")),
                seedSummaryBySource = mapOf(SourceKey.PIXIV to listOf("seed")),
                seedId = "seed",
            )
            blacklistAdditions = added
            blacklistStarted = started
            blacklistRelease = release
        }
        val viewModel = ForYouViewModel(engine, SavedStateHandle(), coroutineScope = this)
        advanceUntilIdle()
        viewModel.synchronizeEnvironment(AppSettings(), activeProfileLikesCount = 1)
        advanceUntilIdle()

        val feedback = async { viewModel.effects.first { it is ForYouEffect.SeedHidden } }
        viewModel.onAction(ForYouAction.BlacklistCurrentSeed)
        started.await()

        engine.settingsChangedResult = true
        viewModel.synchronizeEnvironment(AppSettings(), activeProfileLikesCount = 1)
        release.complete(Unit)
        advanceUntilIdle()

        val hidden = feedback.await() as ForYouEffect.SeedHidden
        assertEquals(added, hidden.entries)
    }

    @Test
    fun `duration lane advances past eight negative decisions to enrich ninth immutably`() = runTest {
        val posts = (1..9).map { index -> animatedTestPost(sourcePostId = "animated-$index") }
        val ninth = posts.last()
        val engine = FakeForYouRouteEngine().apply {
            current = current.copy(activeProfileLikesCount = 1, results = posts, seedId = "seed")
        }
        val enricher = TestAnimatedDurationEnricher { post ->
            9_000L.takeIf { post.id == ninth.id }
        }
        val viewModel = ForYouViewModel(
            engine,
            SavedStateHandle(),
            coroutineScope = this,
            animatedDurationEnricher = enricher,
        )
        advanceUntilIdle()

        viewModel.onAction(ForYouAction.RequestAnimatedDurationEnrichment("seed"))
        advanceUntilIdle()

        assertEquals(posts.map { it.id }, enricher.requestedPostIds)
        assertEquals(9_000L, viewModel.state.value.results.last().durationMs)
        assertNull(ninth.durationMs)
    }

    @Test
    fun `duration lane coalesces results appended while a batch is active`() = runTest {
        val first = animatedTestPost(sourcePostId = "first")
        val appended = animatedTestPost(sourcePostId = "appended")
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val engine = FakeForYouRouteEngine().apply {
            current = current.copy(
                activeProfileLikesCount = 1,
                results = listOf(first),
                seedId = "seed",
                canLoadMore = true,
            )
            pageResults = listOf(appended)
        }
        val enricher = TestAnimatedDurationEnricher { post ->
            if (post.id == first.id) {
                firstStarted.complete(Unit)
                releaseFirst.await()
                1_000L
            } else {
                2_000L
            }
        }
        val viewModel = ForYouViewModel(
            engine,
            SavedStateHandle(),
            coroutineScope = this,
            animatedDurationEnricher = enricher,
        )
        advanceUntilIdle()

        viewModel.onAction(ForYouAction.RequestAnimatedDurationEnrichment("seed"))
        runCurrent()
        firstStarted.await()
        viewModel.onAction(ForYouAction.LoadNextPage)
        runCurrent()
        assertEquals(listOf(first.id, appended.id), viewModel.state.value.results.map(Post::id))
        viewModel.onAction(ForYouAction.RequestAnimatedDurationEnrichment("seed"))
        releaseFirst.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(first.id, appended.id), enricher.requestedPostIds)
        assertEquals(listOf(1_000L, 2_000L), viewModel.state.value.results.map(Post::durationMs))
    }

    @Test
    fun `duration completion from replaced seed cannot update fresh results`() = runTest {
        val oldPost = animatedTestPost(sourcePostId = "shared", title = "old")
        val freshPost = animatedTestPost(sourcePostId = "shared", title = "fresh")
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val engine = FakeForYouRouteEngine().apply {
            current = current.copy(activeProfileLikesCount = 1, results = listOf(oldPost), seedId = "old-seed")
        }
        val enricher = TestAnimatedDurationEnricher {
            started.complete(Unit)
            release.await()
            6_000L
        }
        val viewModel = ForYouViewModel(
            engine,
            SavedStateHandle(),
            coroutineScope = this,
            animatedDurationEnricher = enricher,
        )
        advanceUntilIdle()
        viewModel.onAction(ForYouAction.RequestAnimatedDurationEnrichment("old-seed"))
        runCurrent()
        started.await()

        engine.current = engine.current.copy(results = listOf(freshPost), seedId = "fresh-seed")
        viewModel.rememberResolvedPost(freshPost)
        release.complete(Unit)
        advanceUntilIdle()

        assertEquals("fresh", viewModel.state.value.results.single().title)
        assertNull(viewModel.state.value.results.single().durationMs)
    }
}

private class FakeForYouRouteEngine(
    availableSources: List<SourceKey> = listOf(SourceKey.PIXIV),
) : ForYouRouteEngine {
    var current = ForYouCoordinatorSnapshot(
        activeProfileId = defaultRecommendationProfiles().first().profileId,
        activeProfileLikesCount = 0,
        availableSources = availableSources,
        selectedSource = null,
        sortMode = SortMode.NEWEST,
        seedId = "init",
        seedSummaryBySource = emptyMap(),
        results = emptyList(),
        statuses = emptyList(),
        loading = false,
        loadingMore = false,
        canLoadMore = false,
        errorMessage = null,
    )
    var initializeCalls = 0
    var restoredSource: SourceKey? = null
    var restoredSort: SortMode? = null
    var refreshResult: List<Post> = emptyList()
    var replayResult: List<Post> = emptyList()
    var replayedSearch: Pair<Map<SourceKey, List<String>>, SortMode>? = null
    var refreshCanLoadMore = false
    var pageResults: List<Post> = emptyList()
    var pageError: String? = null
    var blacklistAdditions = listOf(
        ForYouBlacklistEntry(SourceKey.PIXIV, listOf("seed")),
    )
    var undoneBlacklist: Pair<String, List<ForYouBlacklistEntry>>? = null
    var settingsChangedResult = false
    var availabilityChangedResult = false
    var firstRefreshStarted: CompletableDeferred<Unit>? = null
    var firstRefreshRelease: CompletableDeferred<Unit>? = null
    var firstRefreshResult: List<Post> = emptyList()
    var blacklistStarted: CompletableDeferred<Unit>? = null
    var blacklistRelease: CompletableDeferred<Unit>? = null
    private var refreshCalls = 0

    override suspend fun initialize() {
        initializeCalls += 1
    }

    override fun restoreRouteInputs(source: SourceKey?, sort: SortMode?) {
        restoredSource = source
        restoredSort = sort
        current = current.copy(
            selectedSource = source,
            sortMode = sort ?: current.sortMode,
        )
    }

    override fun snapshot(
        profiles: List<RecommendationProfile>,
        blacklistEntries: List<ForYouBlacklistEntry>,
    ): ForYouCoordinatorSnapshot = current.copy(
        profiles = profiles,
        blacklistEntries = blacklistEntries,
    )

    override fun onSettingsChanged(settings: AppSettings): Boolean {
        current = current.copy(activeProfileId = settings.activeProfileId)
        return settingsChangedResult
    }

    override fun onAvailableSourcesChanged(): Boolean = availabilityChangedResult

    override suspend fun refresh(shuffle: Boolean) {
        refreshCalls += 1
        val started = firstRefreshStarted
        val release = firstRefreshRelease
        if (refreshCalls == 1 && started != null && release != null) {
            withContext(NonCancellable) {
                started.complete(Unit)
                release.await()
                current = current.copy(
                    activeProfileLikesCount = 1,
                    results = firstRefreshResult,
                    seedId = "stale-seed",
                    canLoadMore = false,
                    errorMessage = null,
                )
            }
            return
        }
        current = current.copy(
            activeProfileLikesCount = 1,
            results = refreshResult,
            seedId = if (refreshResult.isEmpty()) "empty-seed" else "seed",
            canLoadMore = refreshCanLoadMore,
            errorMessage = null,
        )
    }

    override suspend fun selectProfile(settings: AppSettings, profileId: String) {
        current = current.copy(activeProfileId = profileId)
        refresh(shuffle = false)
    }

    override suspend fun setSourceSelection(source: SourceKey?) {
        current = current.copy(selectedSource = source)
        refresh(shuffle = false)
    }

    override suspend fun setSortMode(sort: SortMode) {
        current = current.copy(sortMode = sort)
        refresh(shuffle = false)
    }

    override suspend fun replaySearch(seedBySource: Map<SourceKey, List<String>>, sort: SortMode) {
        replayedSearch = seedBySource to sort
        current = current.copy(
            activeProfileLikesCount = 0,
            selectedSource = seedBySource.keys.singleOrNull(),
            sortMode = sort,
            seedSummaryBySource = seedBySource,
            seedId = "historical",
            results = replayResult,
        )
    }

    override suspend fun blacklistCurrentSeedAndRefresh(): List<ForYouBlacklistEntry> {
        blacklistStarted?.complete(Unit)
        blacklistRelease?.let { release ->
            withContext(NonCancellable) { release.await() }
        }
        current = current.copy(
            results = emptyList(),
            seedSummaryBySource = emptyMap(),
            seedId = "empty-seed",
            canLoadMore = false,
        )
        return blacklistAdditions
    }

    override suspend fun undoBlacklistAndRefresh(profileId: String, entries: List<ForYouBlacklistEntry>) {
        undoneBlacklist = profileId to entries
        refresh(shuffle = true)
    }

    override suspend fun loadNextPage() {
        current = current.copy(
            results = current.results + pageResults,
            errorMessage = pageError,
            canLoadMore = false,
        )
    }

    override fun clear() {
        current = current.copy(
            activeProfileLikesCount = 0,
            results = emptyList(),
            seedId = "empty",
            seedSummaryBySource = emptyMap(),
            canLoadMore = false,
        )
    }

    override suspend fun resolvePost(postId: PostId): Post? = null
    override fun rememberResolvedPost(post: Post) {
        val index = current.results.indexOfFirst { candidate -> candidate.id == post.id }
        if (index < 0) return
        current = current.copy(
            results = current.results.toMutableList().apply { this[index] = post },
        )
    }
}
