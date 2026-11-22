package com.theoriacodex.app.di

import com.theoriacodex.app.search.SearchCoordinator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.theoriacodex.app.search.testSearchCoordinator
import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.adapter.SourceCapabilities
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.UnifiedSearchOrchestrator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CompositeSourceAdapterRegistryTest {
    @Test
    fun `capability changes preserve registry coordinator and orchestrator identity`() {
        val pixiv = FakeAdapter(SourceKey.PIXIV)
        val rule34 = FakeAdapter(SourceKey.RULE34XXX)
        val delegate = FakeRegistry(mapOf(pixiv.sourceKey to pixiv, rule34.sourceKey to rule34))
        val capabilities = MutableStateFlow(setOf(SourceKey.PIXIV))
        val registry = CompositeSourceAdapterRegistry(delegate, capabilities, noCustomSites)
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
        val registry = CompositeSourceAdapterRegistry(
            potentialBuiltIns = delegate,
            availableBuiltIns = MutableStateFlow(setOf(SourceKey.PIXIV, SourceKey.RULE34XXX)),
            custom = noCustomSites,
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
    fun `capability removal normalizes the active search's sources without reconstruction`() = runTest {
        val pixiv = FakeAdapter(SourceKey.PIXIV)
        val rule34 = FakeAdapter(SourceKey.RULE34XXX)
        val capabilities = MutableStateFlow(setOf(SourceKey.PIXIV, SourceKey.RULE34XXX))
        val registry = CompositeSourceAdapterRegistry(
            potentialBuiltIns = FakeRegistry(mapOf(pixiv.sourceKey to pixiv, rule34.sourceKey to rule34)),
            availableBuiltIns = capabilities,
            custom = noCustomSites,
        )
        val search = testSearchCoordinator(registry)
        search.initializeRoute()

        capabilities.value = setOf(SourceKey.PIXIV)

        assertEquals(listOf(SourceKey.PIXIV), search.availableSources)
    }
}

private val noCustomSites = MutableStateFlow(CustomSourceSnapshot.EMPTY)

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
    )

    override suspend fun search(query: Query, pageToken: String?): Page<Post> {
        return Page(items = emptyList(), nextPageToken = null)
    }

    override suspend fun trendingTags(limit: Int): List<TagSuggestion> = emptyList()

    override suspend fun autocompleteTags(prefix: String, limit: Int): List<TagSuggestion> = emptyList()

    override suspend fun resolvePost(id: PostId): Post? = null
}
