package com.theoriacodex.app.recommend

import com.theoriacodex.app.testing.testPost
import com.theoriacodex.data.repository.InMemoryLikesRepository
import com.theoriacodex.data.repository.InMemoryRecentsRepository
import com.theoriacodex.data.repository.InMemorySettingsRepository
import com.theoriacodex.data.repository.InMemoryStatisticsRepository
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.LikedPost
import com.theoriacodex.data.repository.LikesRepository
import com.theoriacodex.data.repository.SourceRuntimeSettings
import com.theoriacodex.data.repository.RecentSearchKind
import com.theoriacodex.data.repository.defaultRecommendationProfiles
import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.adapter.QuickQueryKind
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.adapter.SourceCapabilities
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.UnifiedSearchOrchestrator
import com.theoriacodex.domain.orchestration.SourceRunState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ForYouCoordinatorTest {
    @Test
    fun `cold fallback sources overlap and retain deterministic source order`() = runTest {
        val adapters = listOf(SourceKey.GELBOORU, SourceKey.PIXIV).map { source ->
            object : SourceAdapter by FakeAdapter(source, source.name) {
                override suspend fun trendingTags(limit: Int): List<TagSuggestion> {
                    delay(1_000L)
                    return listOf(TagSuggestion("favorite", "tag", 1))
                }
            }
        }
        val coordinator = testForYouCoordinator(registry = registryOf(*adapters.toTypedArray()))
        coordinator.initialize()
        val started = testScheduler.currentTime
        coordinator.refresh(shuffle = false)
        assertEquals(1_000L, testScheduler.currentTime - started)
        assertEquals(setOf(SourceKey.GELBOORU, SourceKey.PIXIV), coordinator.results.map { it.id.source }.toSet())
    }

    @Test
    fun `blacklist retries train each source once and a new root retrains`() = runTest {
        val profileId = defaultRecommendationProfiles().first().profileId
        val tagsBySource = listOf(SourceKey.GELBOORU, SourceKey.PIXIV)
            .associateWith { CountingTags(listOf("only_seed")) }
        val likes = object : LikesRepository by InMemoryLikesRepository() {
            override fun observeLikes(profileId: String) = flowOf(tagsBySource.map { (source, tags) ->
                LikedPost(profileId, PostId(source, "liked"), 1L, tags)
            })
        }
        val settings = InMemorySettingsRepository()
        tagsBySource.keys.forEach { source ->
            settings.addForYouBlacklistEntry(profileId, source, listOf("only_seed"))
        }
        val coordinator = testForYouCoordinator(
            registry = registryOf(FakeAdapter(SourceKey.GELBOORU, "gelbooru"), FakeAdapter(SourceKey.PIXIV, "pixiv")),
            settingsRepository = settings,
            likesRepository = likes,
        )
        coordinator.initialize()

        coordinator.refresh(shuffle = false)

        assertTrue(coordinator.results.isEmpty())
        assertEquals(listOf(1, 1), tagsBySource.values.map(CountingTags::reads))

        coordinator.refresh(shuffle = false)

        assertEquals(listOf(2, 2), tagsBySource.values.map(CountingTags::reads))
    }

    @Test
    fun `usable likes do not request trending before a successful personalized search`() = runTest {
        val adapter = FakeAdapter(SourceKey.PIXIV, "pixiv").apply {
            trendingCancellation = CancellationException("unused fallback must not block personalized search")
        }
        val likes = InMemoryLikesRepository()
        likes.toggleLike(defaultRecommendationProfiles().first().profileId, PostId(SourceKey.PIXIV, "liked"), listOf("cloud"))
        val coordinator = testForYouCoordinator(registry = registryOf(adapter), likesRepository = likes)
        coordinator.initialize()

        coordinator.refresh(shuffle = false)

        assertEquals(listOf("cloud"), adapter.lastSearchQuery?.includeTags)
        assertEquals(0, adapter.trendingRequests)
        assertTrue(coordinator.results.isNotEmpty())
    }

    @Test
    fun `accepted recommendation root records one FYP search and pagination records none`() = runTest {
        val recents = InMemoryRecentsRepository(clock = { 100L })
        val likes = InMemoryLikesRepository()
        val statistics = InMemoryStatisticsRepository()
        likes.toggleLike(
            profileId = defaultRecommendationProfiles().first().profileId,
            postId = PostId(SourceKey.PIXIV, "liked-pixiv"),
            tags = listOf("favorite"),
        )
        val coordinator = testForYouCoordinator(
            registry = registryOf(FakeAdapter(SourceKey.PIXIV, "pixiv-post")),
            settingsRepository = InMemorySettingsRepository(),
            likesRepository = likes,
            recentsRepository = recents,
            statisticsRepository = statistics,
        )

        coordinator.initialize()
        coordinator.refresh(shuffle = false)
        val rootEntries = recents.observeSearches().first()
        coordinator.loadNextPage()

        val entry = rootEntries.single()
        assertEquals(RecentSearchKind.FYP, entry.kind)
        assertEquals(listOf(SourceKey.PIXIV), entry.sources)
        assertEquals(listOf("favorite"), entry.query.includeTags)
        assertEquals(mapOf(SourceKey.PIXIV to listOf("favorite")), entry.sourceTags)
        assertEquals("for_you:${coordinator.seedId}", entry.queryHash)
        assertEquals(rootEntries, recents.observeSearches().first())
        assertTrue(recents.observeWatchedPosts().first().isEmpty())
        assertEquals(1, recents.observeActivity().first().size)
        assertEquals(1L, statistics.observeStatistics().first().forYouSearchCount)
    }

    @Test
    fun `saved FYP search replays exact source tags and sort`() = runTest {
        val pixiv = FakeAdapter(SourceKey.PIXIV, "pixiv-post")
        val gelbooru = FakeAdapter(SourceKey.GELBOORU, "gelbooru-post")
        val coordinator = testForYouCoordinator(
            registry = registryOf(pixiv, gelbooru),
            settingsRepository = InMemorySettingsRepository(),
        )
        val seed = linkedMapOf(
            SourceKey.GELBOORU to listOf("gelbooru seed"),
            SourceKey.PIXIV to listOf("pixiv seed", "night"),
        )

        coordinator.initialize()
        coordinator.replaySearch(seed, SortMode.TOP)

        assertEquals(seed, coordinator.seedSummaryBySource)
        assertEquals(SortMode.TOP, coordinator.sortMode)
        assertNull(coordinator.selectedSource)
        assertEquals(listOf("gelbooru seed"), gelbooru.lastSearchQuery?.includeTags)
        assertEquals(listOf("pixiv seed", "night"), pixiv.lastSearchQuery?.includeTags)
        assertEquals(SortMode.TOP, pixiv.lastSearchQuery?.sort)
    }

    @Test
    fun `shuffled recommendations use the injected seed source`() = runTest {
        var seedReads = 0
        val coordinator = testForYouCoordinator(
            registry = registryOf(FakeAdapter(SourceKey.PIXIV, "pixiv-post")),
            settingsRepository = InMemorySettingsRepository(),
            seedSource = {
                seedReads += 1
                42L
            },
        )

        coordinator.initialize()
        coordinator.refresh(shuffle = true)
        coordinator.refresh(shuffle = false)

        assertEquals(1, seedReads)
    }

    @Test
    fun `source selection constrains recommendation tags and content then unified restores both sources`() = runTest {
        val pixiv = FakeAdapter(SourceKey.PIXIV, "pixiv-post")
        val gelbooru = FakeAdapter(SourceKey.GELBOORU, "gelbooru-post")
        val likesRepository = InMemoryLikesRepository()
        val profileId = defaultRecommendationProfiles().first().profileId
        likesRepository.toggleLike(
            profileId = profileId,
            postId = PostId(SourceKey.PIXIV, "liked-pixiv"),
            tags = listOf("pixiv favorite"),
        )
        likesRepository.toggleLike(
            profileId = profileId,
            postId = PostId(SourceKey.GELBOORU, "liked-gelbooru"),
            tags = listOf("gelbooru favorite"),
        )
        val coordinator = testForYouCoordinator(
            registry = registryOf(pixiv, gelbooru),
            settingsRepository = InMemorySettingsRepository(),
            likesRepository = likesRepository,
        )

        coordinator.initialize()
        coordinator.refresh(shuffle = false)

        assertEquals(listOf(SourceKey.GELBOORU, SourceKey.PIXIV), coordinator.availableSourceSelections)
        assertEquals(setOf(SourceKey.PIXIV, SourceKey.GELBOORU), coordinator.seedSummaryBySource.keys)
        assertEquals(setOf(SourceKey.PIXIV, SourceKey.GELBOORU), coordinator.results.map { it.id.source }.toSet())

        coordinator.setSourceSelection(SourceKey.PIXIV)

        assertEquals(SourceKey.PIXIV, coordinator.selectedSource)
        assertEquals(setOf(SourceKey.PIXIV), coordinator.seedSummaryBySource.keys)
        assertTrue(coordinator.results.isNotEmpty())
        assertTrue(coordinator.results.all { post -> post.id.source == SourceKey.PIXIV })
        assertEquals(listOf("pixiv favorite"), pixiv.lastSearchQuery?.includeTags)
        val gelbooruRequestsAfterSelection = gelbooru.requestedPageTokens.size

        coordinator.loadNextPage()

        assertTrue(coordinator.results.all { post -> post.id.source == SourceKey.PIXIV })
        assertEquals(listOf(null, null, "next"), pixiv.requestedPageTokens)
        assertEquals(gelbooruRequestsAfterSelection, gelbooru.requestedPageTokens.size)

        coordinator.setSourceSelection(null)

        assertEquals(null, coordinator.selectedSource)
        assertEquals(setOf(SourceKey.PIXIV, SourceKey.GELBOORU), coordinator.seedSummaryBySource.keys)
        assertEquals(setOf(SourceKey.PIXIV, SourceKey.GELBOORU), coordinator.results.map { it.id.source }.toSet())
    }

    @Test
    fun `blacklisting the only personalized seed leaves an honest empty feed`() = runTest {
        val adapter = FakeAdapter(SourceKey.PIXIV, "pixiv-post")
        val likesRepository = InMemoryLikesRepository()
        val settingsRepository = InMemorySettingsRepository()
        val profileId = defaultRecommendationProfiles().first().profileId
        likesRepository.toggleLike(
            profileId = profileId,
            postId = PostId(SourceKey.PIXIV, "liked-pixiv"),
            tags = listOf("only seed"),
        )
        settingsRepository.addForYouBlacklistEntry(
            profileId = profileId,
            source = SourceKey.PIXIV,
            tags = listOf("only seed"),
        )
        val coordinator = testForYouCoordinator(
            registry = registryOf(adapter),
            settingsRepository = settingsRepository,
            likesRepository = likesRepository,
        )

        coordinator.initialize()
        coordinator.refresh(shuffle = false)

        assertTrue(coordinator.results.isEmpty())
        assertTrue(coordinator.seedSummaryBySource.isEmpty())
        assertEquals("empty-seed", coordinator.seedId)
        assertFalse(coordinator.canLoadMore)
        assertNull(coordinator.errorMessage)
        assertTrue(adapter.requestedPageTokens.isEmpty())
    }

    @Test
    fun `one failed source does not discard healthy recommendations`() = runTest {
        val healthy = FakeAdapter(SourceKey.PIXIV, "pixiv-post")
        val failing = FakeAdapter(SourceKey.GELBOORU, "gelbooru-post", failSearch = true)
        val likesRepository = InMemoryLikesRepository()
        val profileId = defaultRecommendationProfiles().first().profileId
        likesRepository.toggleLike(profileId, PostId(SourceKey.PIXIV, "liked-pixiv"), listOf("pixiv seed"))
        likesRepository.toggleLike(profileId, PostId(SourceKey.GELBOORU, "liked-gelbooru"), listOf("gelbooru seed"))
        val coordinator = testForYouCoordinator(
            registry = registryOf(healthy, failing),
            settingsRepository = InMemorySettingsRepository(),
            likesRepository = likesRepository,
        )

        coordinator.initialize()
        coordinator.refresh(shuffle = false)

        assertEquals(listOf(SourceKey.PIXIV), coordinator.results.map { post -> post.id.source })
        assertEquals(setOf(SourceKey.PIXIV, SourceKey.GELBOORU), coordinator.seedSummaryBySource.keys)
        assertTrue(coordinator.statuses.any { status -> status.source == SourceKey.GELBOORU })
        assertNull(coordinator.errorMessage)
    }

    @Test
    fun `failed page continuation retires while healthy source posts are retained`() = runTest {
        val healthy = FakeAdapter(SourceKey.PIXIV, "healthy")
        val failing = FakeAdapter(SourceKey.GELBOORU, "failing", failPaging = true)
        val likes = InMemoryLikesRepository()
        val profileId = defaultRecommendationProfiles().first().profileId
        likes.toggleLike(profileId, PostId(SourceKey.PIXIV, "liked"), listOf("cloud"))
        likes.toggleLike(profileId, PostId(SourceKey.GELBOORU, "liked"), listOf("cloud"))
        val coordinator = testForYouCoordinator(registry = registryOf(healthy, failing), likesRepository = likes)
        coordinator.initialize()
        coordinator.refresh(shuffle = false)

        coordinator.loadNextPage()
        coordinator.loadNextPage()

        assertEquals(1, failing.pageAttempts)
        assertEquals(1, healthy.pageAttempts)
        assertEquals(3, coordinator.results.size)
        assertEquals(SourceRunState.FAILED, coordinator.statuses.single { it.source == SourceKey.GELBOORU }.state)
        assertEquals(SourceRunState.SUCCESS, coordinator.statuses.single { it.source == SourceKey.PIXIV }.state)
        assertFalse(coordinator.canLoadMore)
    }

    @Test
    fun `cancellation while loading fallback tags propagates without replacing current posts`() = runTest {
        val adapter = FakeAdapter(SourceKey.PIXIV, "pixiv-post")
        val likesRepository = InMemoryLikesRepository()
        val profileId = defaultRecommendationProfiles().first().profileId
        likesRepository.toggleLike(
            profileId = profileId,
            postId = PostId(SourceKey.PIXIV, "liked-pixiv"),
            tags = listOf("100users入り"),
        )
        val coordinator = testForYouCoordinator(
            registry = registryOf(adapter),
            settingsRepository = InMemorySettingsRepository(),
            likesRepository = likesRepository,
        )
        adapter.trendingCandidates = listOf(TagSuggestion(text = "favorite", type = null, count = 1))
        coordinator.initialize()
        coordinator.refresh(shuffle = false)
        val postsBeforeCancellation = coordinator.results
        val expected = CancellationException("recommendation request superseded")
        adapter.trendingCancellation = expected

        var thrown: CancellationException? = null
        try {
            coordinator.refresh(shuffle = false)
        } catch (error: CancellationException) {
            thrown = error
        }

        assertEquals(expected.message, thrown?.message)
        assertEquals(postsBeforeCancellation, coordinator.results)
        assertFalse(coordinator.loading)
        assertNull(coordinator.errorMessage)
    }

    @Test
    fun `capability removal cancels in flight source feed before replacement publishes`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val pixiv = FakeAdapter(SourceKey.PIXIV, "pixiv-current")
        val rule34 = FakeAdapter(
            sourceKey = SourceKey.RULE34XXX,
            postId = "rule34-stale",
            searchStarted = started,
            searchRelease = release,
        )
        val capabilities = MutableStateFlow(setOf(SourceKey.PIXIV, SourceKey.RULE34XXX))
        val registry = mutableRegistryOf(capabilities, pixiv, rule34)
        val likesRepository = InMemoryLikesRepository()
        val recentsRepository = InMemoryRecentsRepository()
        val profileId = defaultRecommendationProfiles().first().profileId
        likesRepository.toggleLike(profileId, PostId(SourceKey.PIXIV, "liked-pixiv"), listOf("pixiv seed"))
        likesRepository.toggleLike(profileId, PostId(SourceKey.RULE34XXX, "liked-rule34"), listOf("rule34 seed"))
        val coordinator = testForYouCoordinator(
            registry = registry,
            settingsRepository = InMemorySettingsRepository(),
            likesRepository = likesRepository,
            recentsRepository = recentsRepository,
        )
        coordinator.initialize()

        val staleRequest = backgroundScope.launch {
            coordinator.setSourceSelection(SourceKey.RULE34XXX)
        }
        started.await()

        capabilities.value = setOf(SourceKey.PIXIV)
        assertTrue(coordinator.onAvailableSourcesChanged())
        assertNull(coordinator.selectedSource)
        assertFalse(coordinator.loading)

        coordinator.refresh(shuffle = false)
        release.complete(Unit)
        staleRequest.join()

        assertEquals(listOf(SourceKey.PIXIV), coordinator.results.map { it.id.source }.distinct())
        assertEquals("pixiv-current", coordinator.results.single().id.sourcePostId)
        assertEquals(
            listOf(SourceKey.PIXIV),
            recentsRepository.observeSearches().first().flatMap { it.sources }.distinct(),
        )
        assertFalse(coordinator.loading)
        assertNull(coordinator.errorMessage)
    }

    @Test
    fun `settings change supersedes in flight feed before replacement publishes`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val pixiv = FakeAdapter(SourceKey.PIXIV, "pixiv-current")
        val rule34 = FakeAdapter(
            sourceKey = SourceKey.RULE34XXX,
            postId = "rule34-stale",
            searchStarted = started,
            searchRelease = release,
        )
        val likesRepository = InMemoryLikesRepository()
        val profileId = defaultRecommendationProfiles().first().profileId
        likesRepository.toggleLike(profileId, PostId(SourceKey.PIXIV, "liked-pixiv"), listOf("pixiv seed"))
        likesRepository.toggleLike(profileId, PostId(SourceKey.RULE34XXX, "liked-rule34"), listOf("rule34 seed"))
        val coordinator = testForYouCoordinator(
            registry = registryOf(pixiv, rule34),
            settingsRepository = InMemorySettingsRepository(),
            likesRepository = likesRepository,
        )
        coordinator.initialize()

        val staleRequest = backgroundScope.launch { coordinator.refresh(shuffle = false) }
        started.await()

        assertTrue(
            coordinator.onSettingsChanged(
                AppSettings(
                    runtime = SourceRuntimeSettings(enabledSources = setOf(SourceKey.PIXIV)),
                )
            )
        )
        coordinator.refresh(shuffle = false)
        release.complete(Unit)
        staleRequest.join()

        assertEquals(listOf(SourceKey.PIXIV), coordinator.results.map { it.id.source }.distinct())
        assertEquals("pixiv-current", coordinator.results.single().id.sourcePostId)
        assertFalse(coordinator.loading)
        assertNull(coordinator.errorMessage)
    }

    private fun registryOf(vararg adapters: SourceAdapter): SourceAdapterRegistry {
        val adaptersBySource = adapters.associateBy { adapter -> adapter.sourceKey }
        val orchestrator = UnifiedSearchOrchestrator(adaptersBySource)
        return object : SourceAdapterRegistry {
            override fun availableSources(): Set<SourceKey> = adaptersBySource.keys
            override fun adapterFor(sourceKey: SourceKey): SourceAdapter? = adaptersBySource[sourceKey]
            override fun unifiedOrchestrator(): UnifiedSearchOrchestrator = orchestrator
        }
    }

    private fun mutableRegistryOf(
        capabilities: MutableStateFlow<Set<SourceKey>>,
        vararg adapters: SourceAdapter,
    ): SourceAdapterRegistry {
        val adaptersBySource = adapters.associateBy(SourceAdapter::sourceKey)
        val orchestrator = UnifiedSearchOrchestrator(adaptersBySource)
        return object : SourceAdapterRegistry {
            override fun availableSources(): Set<SourceKey> = adaptersBySource.keys.intersect(capabilities.value)
            override fun adapterFor(sourceKey: SourceKey): SourceAdapter? {
                return adaptersBySource[sourceKey]?.takeIf { sourceKey in capabilities.value }
            }
            override fun unifiedOrchestrator(): UnifiedSearchOrchestrator = orchestrator
        }
    }

    private class CountingTags(private val values: List<String>) : AbstractList<String>() {
        var reads = 0
            private set
        override val size: Int get() = values.size
        override fun get(index: Int): String {
            reads += 1
            return values[index]
        }
    }

    private class FakeAdapter(
        override val sourceKey: SourceKey,
        postId: String,
        private val failSearch: Boolean = false,
        private val failPaging: Boolean = false,
        private val searchStarted: CompletableDeferred<Unit>? = null,
        private val searchRelease: CompletableDeferred<Unit>? = null,
    ) : SourceAdapter {
        override val capabilities = SourceCapabilities(
            supportsSortNewest = true,
            supportsSortPopular = true,
            supportsSortTop = true,
            supportsSortRandom = true,
            supportsExcludeTagsServerSide = true,
            supportsDateRangeServerSide = true,
            supportsMinScoreServerSide = true,
            requiresCredentials = false,
        )
        private val post = testPost(
            source = sourceKey,
            sourcePostId = postId,
            full = null,
            pageUrl = null,
            width = 100,
            height = 100,
            canonicalTags = listOf("favorite"),
            rawTags = listOf("favorite"),
            authorName = null,
            createdAtEpochMs = null,
        )
        var lastSearchQuery: Query? = null
            private set
        val requestedPageTokens = mutableListOf<String?>()
        var trendingCancellation: CancellationException? = null
        var trendingCandidates: List<TagSuggestion> = emptyList()
        var trendingRequests = 0
        var pageAttempts = 0

        override suspend fun search(query: Query, pageToken: String?): Page<Post> {
            if (pageToken != null) {
                pageAttempts += 1
                if (failPaging) error("$sourceKey page failed")
            }
            if (failSearch) error("$sourceKey failed")
            searchStarted?.complete(Unit)
            searchRelease?.let { gate ->
                withContext(NonCancellable) { gate.await() }
            }
            lastSearchQuery = query
            requestedPageTokens += pageToken
            val pagePost = if (pageToken == null) {
                post
            } else {
                post.copy(id = post.id.copy(sourcePostId = "${post.id.sourcePostId}-$pageToken"))
            }
            return Page(
                items = listOf(pagePost),
                nextPageToken = if (pageToken == null) "next" else null,
            )
        }

        override suspend fun trendingTags(limit: Int): List<TagSuggestion> {
            trendingRequests += 1
            trendingCancellation?.let { error -> throw error }
            return trendingCandidates
        }

        override suspend fun autocompleteTags(prefix: String, limit: Int): List<TagSuggestion> = emptyList()

        override suspend fun quickQuery(kind: QuickQueryKind): Query {
            return Query(
                mode = QueryMode.Source(sourceKey),
                includeTags = emptyList(),
                excludeTags = emptyList(),
                sort = SortMode.NEWEST,
                dateRange = null,
                minScore = null,
            )
        }

        override suspend fun resolvePost(id: PostId): Post? = post.takeIf { candidate -> candidate.id == id }
    }
}
