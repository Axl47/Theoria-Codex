package com.theoriacodex.app.related

import com.theoriacodex.app.testing.testPost
import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.adapter.QuickQueryKind
import com.theoriacodex.domain.adapter.RelatedPostsSourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.adapter.SourceCapabilities
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.UnifiedSearchOrchestrator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelatedPostsLoaderTest {
    @Test
    fun `loader derives support from adapter and publishes bounded same source unique posts`() = runTest {
        val seed = PostId(SourceKey.PIXIV, "seed")
        val candidates = buildList {
            add(testPost(sourcePostId = "seed"))
            add(testPost(sourcePostId = "one"))
            add(testPost(sourcePostId = "one"))
            add(testPost(source = SourceKey.GELBOORU, sourcePostId = "cross"))
            repeat(8) { index -> add(testPost(sourcePostId = "item-$index")) }
        }
        val adapter = FakeRelatedAdapter(candidates)
        val loader = RelatedPostsLoader(FakeRelatedRegistry(adapter))

        val posts = loader.load(seed)

        assertTrue(loader.supports(SourceKey.PIXIV))
        assertFalse(loader.supports(SourceKey.GELBOORU))
        assertEquals(6, posts.size)
        assertEquals(listOf("one", "item-0", "item-1", "item-2", "item-3", "item-4"), posts.map { it.id.sourcePostId })
    }
}

private class FakeRelatedAdapter(
    private val related: List<Post>,
) : SourceAdapter, RelatedPostsSourceAdapter {
    override val sourceKey: SourceKey = SourceKey.PIXIV
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

    override suspend fun relatedPosts(seed: PostId, limit: Int): List<Post> = related
    override suspend fun search(query: Query, pageToken: String?): Page<Post> = Page(emptyList(), null)
    override suspend fun trendingTags(limit: Int): List<TagSuggestion> = emptyList()
    override suspend fun autocompleteTags(prefix: String, limit: Int): List<TagSuggestion> = emptyList()
    override suspend fun quickQuery(kind: QuickQueryKind): Query = error("unused")
    override suspend fun resolvePost(id: PostId): Post? = null
}

private class FakeRelatedRegistry(
    private val adapter: SourceAdapter,
) : SourceAdapterRegistry {
    override fun availableSources(): Set<SourceKey> = setOf(adapter.sourceKey)
    override fun adapterFor(sourceKey: SourceKey): SourceAdapter? = adapter.takeIf { it.sourceKey == sourceKey }
    override fun unifiedOrchestrator(): UnifiedSearchOrchestrator = UnifiedSearchOrchestrator(
        mapOf(adapter.sourceKey to adapter)
    )
}
