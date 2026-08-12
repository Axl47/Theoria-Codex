package com.theoriacodex.app.di

import com.theoriacodex.app.search.SearchCoordinator
import com.theoriacodex.app.recommend.ForYouCoordinator
import com.theoriacodex.app.recommend.testForYouCoordinator
import com.theoriacodex.app.search.testSearchCoordinator
import com.theoriacodex.data.repository.InMemoryLikesRepository
import com.theoriacodex.data.repository.InMemorySettingsRepository
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AvailabilityAwareSourceAdapterRegistryTest {
    @Test
    fun `capability changes preserve registry coordinator and orchestrator identity`() {
        val pixiv = FakeAdapter(SourceKey.PIXIV)
        val rule34 = FakeAdapter(SourceKey.RULE34XXX)
        val delegate = FakeRegistry(mapOf(pixiv.sourceKey to pixiv, rule34.sourceKey to rule34))
        val capabilities = MutableStateFlow(setOf(SourceKey.PIXIV))
        val registry = AvailabilityAwareSourceAdapterRegistry(delegate, capabilities)
        val coordinator = testSearchCoordinator(registry)
        val orchestrator = registry.unifiedOrchestrator()

        assertEquals(listOf(SourceKey.PIXIV), coordinator.availableSources)
        assertNull(registry.adapterFor(SourceKey.RULE34XXX))

        capabilities.value = setOf(SourceKey.PIXIV, SourceKey.RULE34XXX)

        assertEquals(setOf(SourceKey.PIXIV, SourceKey.RULE34XXX), registry.availableSources())
        assertEquals(listOf(SourceKey.PIXIV, SourceKey.RULE34XXX), coordinator.availableSources)
        assertSame(rule34, registry.adapterFor(SourceKey.RULE34XXX))
        assertSame(orchestrator, registry.unifiedOrchestrator())
    }

    @Test
    fun `capability state cannot expose an adapter absent from the delegate`() {
        val pixiv = FakeAdapter(SourceKey.PIXIV)
        val delegate = FakeRegistry(mapOf(pixiv.sourceKey to pixiv))
        val registry = AvailabilityAwareSourceAdapterRegistry(
            delegate = delegate,
            availableSourceState = MutableStateFlow(setOf(SourceKey.PIXIV, SourceKey.RULE34XXX)),
        )

        assertEquals(setOf(SourceKey.PIXIV), registry.availableSources())
        assertNull(registry.adapterFor(SourceKey.RULE34XXX))
    }

    @Test
    fun `search initialization is idempotent and returns immutable persisted state`() = runTest {
        val pixiv = FakeAdapter(SourceKey.PIXIV)
        val registry = FakeRegistry(mapOf(SourceKey.PIXIV to pixiv))
        val coordinator = testSearchCoordinator(registry)

        val first = coordinator.initializeRoute()
        val second = coordinator.initializeRoute()

        assertEquals(first, second)
        assertEquals(QueryMode.Unified, second.query.mode)
        assertEquals(listOf(SourceKey.PIXIV), second.availableSources)
    }

    @Test
    fun `capability removal normalizes active search and feed selections without reconstruction`() = runTest {
        val pixiv = FakeAdapter(SourceKey.PIXIV)
        val rule34 = FakeAdapter(SourceKey.RULE34XXX)
        val capabilities = MutableStateFlow(setOf(SourceKey.PIXIV, SourceKey.RULE34XXX))
        val registry = AvailabilityAwareSourceAdapterRegistry(
            delegate = FakeRegistry(mapOf(pixiv.sourceKey to pixiv, rule34.sourceKey to rule34)),
            availableSourceState = capabilities,
        )
        val search = testSearchCoordinator(registry)
        search.initializeRoute()

        val likes = InMemoryLikesRepository()
        val profileId = defaultRecommendationProfiles().first().profileId
        likes.toggleLike(
            profileId = profileId,
            postId = PostId(SourceKey.RULE34XXX, "liked"),
            tags = listOf("tag"),
        )
        val forYou = testForYouCoordinator(
            registry = registry,
            settingsRepository = InMemorySettingsRepository(),
            likesRepository = likes,
        )
        forYou.initialize()
        forYou.refresh(shuffle = false)
        forYou.setSourceSelection(SourceKey.RULE34XXX)

        capabilities.value = setOf(SourceKey.PIXIV)

        val searchChange = search.updateEnvironment(com.theoriacodex.data.repository.AppSettings())
        assertTrue(searchChange.sourcesChanged)
        assertEquals(listOf(SourceKey.PIXIV), searchChange.availableSources)
        assertTrue(forYou.onAvailableSourcesChanged())
        assertNull(forYou.selectedSource)
    }
}

private class FakeRegistry(
    private val adapters: Map<SourceKey, SourceAdapter>,
) : SourceAdapterRegistry {
    private val orchestrator = UnifiedSearchOrchestrator(adapters)

    override fun availableSources(): Set<SourceKey> = adapters.keys

    override fun adapterFor(sourceKey: SourceKey): SourceAdapter? = adapters[sourceKey]

    override fun unifiedOrchestrator(): UnifiedSearchOrchestrator = orchestrator
}

private class FakeAdapter(
    override val sourceKey: SourceKey,
) : SourceAdapter {
    override val capabilities = SourceCapabilities(
        supportsSortNewest = true,
        supportsSortPopular = false,
        supportsSortTop = false,
        supportsSortRandom = false,
        supportsExcludeTagsServerSide = false,
        supportsDateRangeServerSide = false,
        supportsMinScoreServerSide = false,
        requiresCredentials = false,
    )

    override suspend fun search(query: Query, pageToken: String?): Page<Post> {
        return Page(items = emptyList(), nextPageToken = null)
    }

    override suspend fun trendingTags(limit: Int): List<TagSuggestion> = emptyList()

    override suspend fun autocompleteTags(prefix: String, limit: Int): List<TagSuggestion> = emptyList()

    override suspend fun quickQuery(kind: QuickQueryKind): Query {
        return Query(
            mode = QueryMode.Source(sourceKey),
            includeTerms = emptyList(),
            excludeTerms = emptyList(),
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = null,
        )
    }

    override suspend fun resolvePost(id: PostId): Post? = null
}
