package com.theoriacodex.app.recommend

import androidx.lifecycle.SavedStateHandle
import com.theoriacodex.app.recommend.state.ForYouAction
import com.theoriacodex.app.testing.testPost
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceUntilIdle
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
    @Test fun `section pins reload native searches and dismissal survives generation`() = runTest {
        val adapter = FakeAdapter(SourceKey.GELBOORU, "section")
        val settings = InMemorySettingsRepository()
        val saved = com.theoriacodex.data.repository.InMemorySavedSearchRepository()
        val service = testForYouCoordinator(registryOf(adapter), settingsRepository = settings,
            likesRepository = liked(SourceKey.GELBOORU), savedSearches = saved)
        val section = service.loadSections(request(service)).single()
        assertEquals(mapOf(SourceKey.GELBOORU to listOf("cloud")), section.feed.seed)
        assertEquals(listOf("cloud"), section.feed.continuation?.queriesBySource?.get(SourceKey.GELBOORU)?.includeTags)
        val pinId = service.pinSection(section, true)
        assertEquals(RecentSearchKind.FYP, saved.observeSavedSearches().first().single().search.kind)
        val reloaded = service.loadSections(request(service)).single()
        assertEquals(pinId, reloaded.savedSearchId)
        service.pinSection(reloaded, false)
        assertTrue(saved.observeSavedSearches().first().isEmpty())
        settings.addForYouBlacklistEntry(defaultRecommendationProfiles().first().profileId,
            SourceKey.GELBOORU, section.feed.seed.getValue(SourceKey.GELBOORU))
        assertTrue(service.loadSections(request(service, settings.observeSettings().first())).isEmpty())
    }

    @Test fun `overview source selection constrains pins and generated sections without history writes`() = runTest {
        val recents = InMemoryRecentsRepository()
        val service = testForYouCoordinator(registryOf(FakeAdapter(SourceKey.GELBOORU, "g"), FakeAdapter(SourceKey.PIXIV, "p")),
            likesRepository = liked(SourceKey.GELBOORU, SourceKey.PIXIV), recentsRepository = recents)
        val sections = service.loadSections(request(service).copy(selectedSource = SourceKey.PIXIV))
        assertEquals(setOf(SourceKey.PIXIV), sections.flatMap { it.feed.seed.keys }.toSet())
        assertTrue(recents.observeSearches().first().isEmpty())
    }

    @Test
    fun `cold fallback acquisition overlaps while seed order remains deterministic`() = runTest {
        val adapters = listOf(SourceKey.PIXIV, SourceKey.GELBOORU).map { source ->
            object : SourceAdapter by FakeAdapter(source, source.name) {
                override suspend fun trendingTags(limit: Int): List<TagSuggestion> {
                    delay(1_000L)
                    return listOf(TagSuggestion("favorite", "tag", 1))
                }
            }
        }
        val service = testForYouCoordinator(registryOf(*adapters.toTypedArray()))
        val started = testScheduler.currentTime
        val result = service.execute(request(service))
        assertEquals(1_000L, testScheduler.currentTime - started)
        assertEquals(listOf(SourceKey.GELBOORU, SourceKey.PIXIV), result.seed.keys.toList())
        assertEquals(result.seed.keys, result.page.items.map { it.id.source }.toSet())
    }

    @Test
    fun `blacklist retries prepare each source once and new roots train again`() = runTest {
        val profileId = defaultRecommendationProfiles().first().profileId
        val tagsBySource = listOf(SourceKey.GELBOORU, SourceKey.PIXIV).associateWith { CountingTags(listOf("only_seed")) }
        val likes = object : LikesRepository by InMemoryLikesRepository() {
            override fun observeLikes(profileId: String) = flowOf(tagsBySource.map { (source, tags) ->
                LikedPost(profileId, PostId(source, "liked"), 1L, tags)
            })
        }
        val settings = InMemorySettingsRepository()
        tagsBySource.keys.forEach { settings.addForYouBlacklistEntry(profileId, it, listOf("only_seed")) }
        val service = testForYouCoordinator(registryOf(FakeAdapter(SourceKey.PIXIV, "p"), FakeAdapter(SourceKey.GELBOORU, "g")),
            settingsRepository = settings, likesRepository = likes)
        val request = request(service, settings.observeSettings().first())
        assertTrue(service.execute(request).page.items.isEmpty())
        assertEquals(listOf(1, 1), tagsBySource.values.map(CountingTags::reads))
        service.execute(request)
        assertEquals(listOf(2, 2), tagsBySource.values.map(CountingTags::reads))
    }

    @Test
    fun `usable likes do not fetch unused fallback and shuffle uses injected seed`() = runTest {
        val adapter = FakeAdapter(SourceKey.PIXIV, "p").apply {
            trendingCancellation = CancellationException("unused fallback")
        }
        val likes = liked(SourceKey.PIXIV)
        var seedReads = 0
        val service = testForYouCoordinator(registryOf(adapter), likesRepository = likes, seedSource = { seedReads++; 42L })
        assertTrue(service.execute(request(service).copy(shuffle = true)).page.items.isNotEmpty())
        service.execute(request(service))
        assertEquals(1, seedReads)
        assertEquals(0, adapter.trendingRequests)
        assertEquals(listOf("cloud"), adapter.lastSearchQuery?.includeTags)
    }

    @Test
    fun `only admitted roots record history and failed roots or pages never do`() = runTest {
        val adapter = FakeAdapter(SourceKey.PIXIV, "p")
        val recents = InMemoryRecentsRepository()
        val statistics = InMemoryStatisticsRepository()
        val service = testForYouCoordinator(registryOf(adapter), likesRepository = liked(SourceKey.PIXIV),
            recentsRepository = recents, statisticsRepository = statistics)
        val result = service.execute(request(service))
        assertTrue(recents.observeSearches().first().isEmpty())
        service.recordAccepted(result)
        val entry = recents.observeSearches().first().single()
        assertEquals(RecentSearchKind.FYP, entry.kind)
        assertEquals(listOf(SourceKey.PIXIV), entry.sources)
        assertEquals(result.seed, entry.sourceTags)
        assertEquals("for_you:${result.seedId}", entry.queryHash)
        service.loadPage(requireNotNull(result.continuation), setOf(SourceKey.PIXIV))
        adapter.failSearch = true
        service.recordAccepted(service.execute(request(service)))
        assertEquals(listOf(entry), recents.observeSearches().first())
        assertEquals(1L, statistics.observeStatistics().first().forYouSearchCount)
        assertTrue(recents.observeWatchedPosts().first().isEmpty())
    }

    @Test
    fun `historical replay preserves exact source tags sort and recorded source order without likes`() = runTest {
        val pixiv = FakeAdapter(SourceKey.PIXIV, "p")
        val gelbooru = FakeAdapter(SourceKey.GELBOORU, "g")
        val recents = InMemoryRecentsRepository()
        val service = testForYouCoordinator(registryOf(pixiv, gelbooru), recentsRepository = recents)
        val seed = linkedMapOf(SourceKey.PIXIV to listOf("pixiv seed", "night"), SourceKey.GELBOORU to listOf("gelbooru seed"))
        val result = service.execute(request(service).copy(sortMode = SortMode.TOP, historicalSeed = seed))
        service.recordAccepted(result)
        assertEquals(seed, result.seed)
        assertEquals(seed.keys.toList(), recents.observeSearches().first().single().sources)
        assertEquals(seed[SourceKey.GELBOORU], gelbooru.lastSearchQuery?.includeTags)
        assertEquals(seed[SourceKey.PIXIV], pixiv.lastSearchQuery?.includeTags)
        assertEquals(SortMode.TOP, pixiv.lastSearchQuery?.sort)
    }

    @Test
    fun `source selection and continuation carry their original queries independently of later roots`() = runTest {
        val pixiv = FakeAdapter(SourceKey.PIXIV, "p")
        val gelbooru = FakeAdapter(SourceKey.GELBOORU, "g")
        val service = testForYouCoordinator(registryOf(pixiv, gelbooru), likesRepository = liked(SourceKey.PIXIV, SourceKey.GELBOORU))
        val selected = service.execute(request(service).copy(selectedSource = SourceKey.PIXIV, sortMode = SortMode.TOP))
        val unified = service.execute(request(service))
        val page = service.loadPage(requireNotNull(selected.continuation), setOf(SourceKey.PIXIV))
        assertEquals(setOf(SourceKey.PIXIV), selected.seed.keys)
        assertEquals(setOf(SourceKey.PIXIV, SourceKey.GELBOORU), unified.seed.keys)
        assertTrue(page.items.all { it.id.source == SourceKey.PIXIV })
        assertEquals(SortMode.TOP, pixiv.lastSearchQuery?.sort)
        assertEquals(listOf(null, null, "next"), pixiv.requestedPageTokens)
        assertEquals(listOf<String?>(null), gelbooru.requestedPageTokens)
    }

    @Test
    fun `blacklisting the only personalized seed returns an honest empty result`() = runTest {
        val adapter = FakeAdapter(SourceKey.PIXIV, "p")
        val service = testForYouCoordinator(registryOf(adapter), likesRepository = liked(SourceKey.PIXIV))
        val change = service.updateBlacklist(defaultRecommendationProfiles().first().profileId,
            listOf(com.theoriacodex.data.repository.ForYouBlacklistEntry(SourceKey.PIXIV, listOf("cloud"))), true)
        val result = service.execute(request(service, change.settings))
        assertEquals("empty-seed", result.seedId)
        assertTrue(result.page.items.isEmpty())
        assertNull(result.continuation)
        assertTrue(adapter.requestedPageTokens.isEmpty())
    }

    @Test
    fun `failed first page retains null retry token without discarding healthy provider`() = runTest {
        val healthy = FakeAdapter(SourceKey.PIXIV, "p")
        val failing = FakeAdapter(SourceKey.GELBOORU, "g", failSearch = true)
        val service = testForYouCoordinator(registryOf(healthy, failing), likesRepository = liked(SourceKey.PIXIV, SourceKey.GELBOORU))
        val result = service.execute(request(service))
        val continuation = requireNotNull(result.continuation)
        assertEquals(listOf(SourceKey.PIXIV), result.page.items.map { it.id.source })
        assertTrue(continuation.pageTokens.containsKey(SourceKey.GELBOORU))
        assertNull(continuation.pageTokens[SourceKey.GELBOORU])
        failing.failSearch = false
        val retry = service.loadPage(continuation, setOf(SourceKey.GELBOORU))
        assertEquals(listOf(SourceKey.GELBOORU), retry.items.map { it.id.source })
        assertEquals(listOf<String?>(null), healthy.requestedPageTokens)
    }

    @Test
    fun `route retries only failed page and records no additional root history`() = runTest {
        val healthy = FakeAdapter(SourceKey.PIXIV, "p")
        val failing = FakeAdapter(SourceKey.GELBOORU, "g", failPaging = true)
        val statistics = InMemoryStatisticsRepository()
        val service = testForYouCoordinator(registryOf(healthy, failing), likesRepository = liked(SourceKey.PIXIV, SourceKey.GELBOORU), statisticsRepository = statistics)
        val owner = ForYouViewModel(engine = service, savedStateHandle = SavedStateHandle(), coroutineScope = this)
        owner.synchronizeEnvironment(AppSettings(), 2)
        advanceUntilIdle()
        owner.onAction(ForYouAction.LoadNextPage)
        advanceUntilIdle()
        val retained = owner.state.value.results
        assertEquals(3, retained.size)
        assertEquals(setOf(SourceKey.GELBOORU), owner.state.value.retryableSources)
        owner.onAction(ForYouAction.LoadNextPage)
        advanceUntilIdle()
        assertEquals(1, failing.pageAttempts)
        failing.failPaging = false
        owner.onAction(ForYouAction.RetrySource(SourceKey.GELBOORU))
        advanceUntilIdle()
        assertEquals(2, failing.pageAttempts)
        assertEquals(1, healthy.pageAttempts)
        assertEquals(retained, owner.state.value.results.take(retained.size))
        assertEquals(4, owner.state.value.results.size)
        assertTrue(owner.state.value.retryableSources.isEmpty())
        assertEquals(1L, statistics.observeStatistics().first().forYouSearchCount)
    }

    @Test
    fun `fallback cancellation propagates and leaves previously returned result immutable`() = runTest {
        val adapter = FakeAdapter(SourceKey.PIXIV, "p").apply {
            trendingCandidates = listOf(TagSuggestion("favorite", null, 1))
        }
        val service = testForYouCoordinator(registryOf(adapter))
        val first = service.execute(request(service))
        adapter.trendingCancellation = CancellationException("superseded")
        var message: String? = null
        try { service.execute(request(service)) } catch (error: CancellationException) { message = error.message }
        assertEquals("superseded", message)
        assertEquals(1, first.page.items.size)
    }

    @Test
    fun `capability removal rejects late results and their history through the real route owner`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val pixiv = FakeAdapter(SourceKey.PIXIV, "fresh")
        val rule34 = FakeAdapter(SourceKey.RULE34XXX, "stale", searchStarted = started, searchRelease = release)
        val capabilities = MutableStateFlow(setOf(SourceKey.PIXIV, SourceKey.RULE34XXX))
        val recents = InMemoryRecentsRepository()
        val service = testForYouCoordinator(mutableRegistryOf(capabilities, pixiv, rule34),
            likesRepository = liked(SourceKey.PIXIV, SourceKey.RULE34XXX), recentsRepository = recents)
        val owner = ForYouViewModel(engine = service, savedStateHandle = SavedStateHandle(), coroutineScope = this)
        owner.synchronizeEnvironment(AppSettings(), 2)
        runCurrent()
        started.await()
        capabilities.value = setOf(SourceKey.PIXIV)
        owner.onSourceAvailabilityChanged()
        runCurrent()
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("fresh"), owner.state.value.results.map { it.id.sourcePostId })
        assertEquals(listOf(SourceKey.PIXIV), recents.observeSearches().first().flatMap { it.sources }.distinct())
    }

    private fun request(service: ForYouCoordinator, settings: AppSettings = AppSettings()) = ForYouFeedRequest(
        settings, service.availableSources(settings), null, SortMode.NEWEST, false, 0,
    )

    private suspend fun liked(vararg sources: SourceKey): LikesRepository = InMemoryLikesRepository().also { likes ->
        sources.forEach { likes.toggleLike(defaultRecommendationProfiles().first().profileId, PostId(it, "liked"), listOf("cloud")) }
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
        var failSearch: Boolean = false,
        var failPaging: Boolean = false,
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
