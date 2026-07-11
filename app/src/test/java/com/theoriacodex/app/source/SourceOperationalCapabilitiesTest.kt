package com.theoriacodex.app.source

import com.theoriacodex.domain.adapter.CreatorPostsSourceAdapter
import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.adapter.QuickQueryKind
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.adapter.SourceCapabilities
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.UnifiedSearchOrchestrator
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceOperationalCapabilitiesTest {
    @Test
    fun `creator browsing comes from available adapter interfaces`() {
        val pixiv = CreatorAdapter(SourceKey.PIXIV)
        val gelbooru = BasicAdapter(SourceKey.GELBOORU)
        val hiddenCreator = CreatorAdapter(SourceKey.IWARA)
        val registry = FakeRegistry(
            adapters = mapOf(
                pixiv.sourceKey to pixiv,
                gelbooru.sourceKey to gelbooru,
                hiddenCreator.sourceKey to hiddenCreator,
            ),
            available = setOf(SourceKey.PIXIV, SourceKey.GELBOORU),
        )

        assertEquals(setOf(SourceKey.PIXIV), registry.creatorBrowsingSources())
    }
}

private class FakeRegistry(
    private val adapters: Map<SourceKey, SourceAdapter>,
    private val available: Set<SourceKey>,
) : SourceAdapterRegistry {
    override fun availableSources(): Set<SourceKey> = available

    override fun adapterFor(sourceKey: SourceKey): SourceAdapter? = adapters[sourceKey]

    override fun unifiedOrchestrator(): UnifiedSearchOrchestrator = UnifiedSearchOrchestrator(adapters)
}

private open class BasicAdapter(
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

    override suspend fun search(query: Query, pageToken: String?): Page<Post> = Page(emptyList(), null)

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

private class CreatorAdapter(sourceKey: SourceKey) : BasicAdapter(sourceKey), CreatorPostsSourceAdapter {
    override suspend fun searchCreatorPosts(
        creator: CreatorProfile,
        pageToken: String?,
    ): Page<Post> = Page(emptyList(), null)
}
