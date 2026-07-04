package com.theoriacodex.domain.orchestration

import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.adapter.QuickQueryKind
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceCapabilities
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.query.CapabilityExclusionReason
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedSearchOrchestratorTest {
    @Test
    fun `excludes source when capability does not support selected sort`() = runTest {
        val query = Query(
            mode = QueryMode.Unified,
            includeTags = listOf("landscape"),
            excludeTags = emptyList(),
            sort = SortMode.TOP,
            dateRange = null,
            minScore = null,
        )
        val orchestrator = UnifiedSearchOrchestrator(
            adaptersBySource = mapOf(
                SourceKey.PIXIV to FakeAdapter(
                    sourceKey = SourceKey.PIXIV,
                    capabilities = SourceCapabilities(
                        supportsSortNewest = true,
                        supportsSortPopular = true,
                        supportsSortTop = false,
                        supportsSortRandom = true,
                        supportsExcludeTagsServerSide = true,
                        supportsDateRangeServerSide = true,
                        supportsMinScoreServerSide = true,
                        requiresCredentials = false,
                    ),
                    posts = listOf(post(SourceKey.PIXIV, "1")),
                ),
                SourceKey.GELBOORU to FakeAdapter(
                    sourceKey = SourceKey.GELBOORU,
                    capabilities = SourceCapabilities(
                        supportsSortNewest = true,
                        supportsSortPopular = true,
                        supportsSortTop = true,
                        supportsSortRandom = true,
                        supportsExcludeTagsServerSide = true,
                        supportsDateRangeServerSide = true,
                        supportsMinScoreServerSide = true,
                        requiresCredentials = false,
                    ),
                    posts = listOf(post(SourceKey.GELBOORU, "2")),
                ),
            )
        )

        val result = orchestrator.search(
            query = query,
            enabledSources = setOf(SourceKey.PIXIV, SourceKey.GELBOORU),
            pageTokens = emptyMap(),
            weights = mapOf(SourceKey.PIXIV to 0.5, SourceKey.GELBOORU to 0.5),
        )

        assertEquals(1, result.items.size)
        val pixivStatus = result.statuses.first { it.source == SourceKey.PIXIV }
        assertEquals(SourceRunState.EXCLUDED, pixivStatus.state)
        assertTrue(CapabilityExclusionReason.SORT_UNSUPPORTED in pixivStatus.exclusionReasons)
    }

    @Test
    fun `interleaves successful sources with weights`() = runTest {
        val orchestrator = UnifiedSearchOrchestrator(
            adaptersBySource = mapOf(
                SourceKey.PIXIV to FakeAdapter(
                    sourceKey = SourceKey.PIXIV,
                    capabilities = supportedCapabilities(),
                    posts = listOf(post(SourceKey.PIXIV, "p1"), post(SourceKey.PIXIV, "p2"), post(SourceKey.PIXIV, "p3")),
                ),
                SourceKey.GELBOORU to FakeAdapter(
                    sourceKey = SourceKey.GELBOORU,
                    capabilities = supportedCapabilities(),
                    posts = listOf(post(SourceKey.GELBOORU, "g1")),
                ),
            )
        )

        val result = orchestrator.search(
            query = sampleQuery(),
            enabledSources = setOf(SourceKey.PIXIV, SourceKey.GELBOORU),
            pageTokens = emptyMap(),
            weights = mapOf(SourceKey.PIXIV to 0.75, SourceKey.GELBOORU to 0.25),
        )

        assertEquals(4, result.items.size)
        assertEquals(SourceKey.PIXIV, result.items.first().id.source)
        assertEquals(2, result.statuses.count { it.state == SourceRunState.SUCCESS })
    }

    @Test
    fun `uses query override only for targeted source`() = runTest {
        val pixivAdapter = FakeAdapter(
            sourceKey = SourceKey.PIXIV,
            capabilities = supportedCapabilities(),
            posts = listOf(post(SourceKey.PIXIV, "p1")),
        )
        val gelbooruAdapter = FakeAdapter(
            sourceKey = SourceKey.GELBOORU,
            capabilities = supportedCapabilities(),
            posts = listOf(post(SourceKey.GELBOORU, "g1")),
        )
        val orchestrator = UnifiedSearchOrchestrator(
            adaptersBySource = mapOf(
                SourceKey.PIXIV to pixivAdapter,
                SourceKey.GELBOORU to gelbooruAdapter,
            )
        )
        val query = sampleQuery()
        val override = query.copy(includeTags = listOf("gelbooru_tag"))

        orchestrator.search(
            query = query,
            enabledSources = setOf(SourceKey.PIXIV, SourceKey.GELBOORU),
            pageTokens = emptyMap(),
            weights = mapOf(SourceKey.PIXIV to 0.5, SourceKey.GELBOORU to 0.5),
            queryOverridesBySource = mapOf(SourceKey.GELBOORU to override),
        )

        assertEquals(listOf("landscape"), pixivAdapter.lastSearchQuery?.includeTags)
        assertEquals(listOf("gelbooru_tag"), gelbooruAdapter.lastSearchQuery?.includeTags)
    }

    @Test
    fun `applies exclude tags client side when source excludes are unsupported`() = runTest {
        val pixivAdapter = FakeAdapter(
            sourceKey = SourceKey.PIXIV,
            capabilities = SourceCapabilities(
                supportsSortNewest = true,
                supportsSortPopular = true,
                supportsSortTop = true,
                supportsSortRandom = true,
                supportsExcludeTagsServerSide = false,
                supportsDateRangeServerSide = true,
                supportsMinScoreServerSide = true,
                requiresCredentials = false,
            ),
            posts = listOf(
                post(SourceKey.PIXIV, "p1", tags = listOf("landscape", "safe")),
                post(SourceKey.PIXIV, "p2", tags = listOf("landscape", "nsfw")),
            ),
        )
        val orchestrator = UnifiedSearchOrchestrator(
            adaptersBySource = mapOf(SourceKey.PIXIV to pixivAdapter)
        )
        val query = Query(
            mode = QueryMode.Unified,
            includeTags = listOf("landscape"),
            excludeTags = listOf("nsfw"),
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = null,
        )

        val result = orchestrator.search(
            query = query,
            enabledSources = setOf(SourceKey.PIXIV),
            pageTokens = emptyMap(),
            weights = mapOf(SourceKey.PIXIV to 1.0),
        )

        assertEquals(1, result.items.size)
        assertEquals("p1", result.items.first().id.sourcePostId)
        assertEquals(SourceRunState.SUCCESS, result.statuses.single().state)
        assertTrue(pixivAdapter.lastSearchQuery?.excludeTags?.isEmpty() == true)
    }

    @Test
    fun `propagates typed source failure reason`() = runTest {
        val orchestrator = UnifiedSearchOrchestrator(
            adaptersBySource = mapOf(
                SourceKey.PIXIV to FakeAdapter(
                    sourceKey = SourceKey.PIXIV,
                    capabilities = supportedCapabilities(),
                    posts = emptyList(),
                    error = SourceAdapterException(
                        reason = SourceFailureReason.AUTH_REQUIRED,
                        message = "missing credentials",
                    ),
                )
            )
        )

        val result = orchestrator.search(
            query = sampleQuery(),
            enabledSources = setOf(SourceKey.PIXIV),
            pageTokens = emptyMap(),
            weights = mapOf(SourceKey.PIXIV to 1.0),
        )

        val status = result.statuses.single()
        assertEquals(SourceRunState.FAILED, status.state)
        assertEquals(SourceFailureReason.AUTH_REQUIRED, status.failureReason)
    }

    private fun sampleQuery(): Query {
        return Query(
            mode = QueryMode.Unified,
            includeTags = listOf("landscape"),
            excludeTags = emptyList(),
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = null,
        )
    }

    private fun post(source: SourceKey, id: String, tags: List<String> = listOf("landscape")): Post {
        return Post(
            id = PostId(source, id),
            preview = ImageRef(url = "https://example.com/$id.jpg", localPath = null, mime = "image/jpeg"),
            full = null,
            pageUrl = null,
            width = 100,
            height = 100,
            canonicalTags = tags,
            rawTags = tags,
            authorName = null,
            createdAtEpochMs = null,
        )
    }

    private fun supportedCapabilities(): SourceCapabilities {
        return SourceCapabilities(
            supportsSortNewest = true,
            supportsSortPopular = true,
            supportsSortTop = true,
            supportsSortRandom = true,
            supportsExcludeTagsServerSide = true,
            supportsDateRangeServerSide = true,
            supportsMinScoreServerSide = true,
            requiresCredentials = false,
        )
    }

    private class FakeAdapter(
        override val sourceKey: SourceKey,
        override val capabilities: SourceCapabilities,
        private val posts: List<Post>,
        private val error: Throwable? = null,
    ) : SourceAdapter {
        var lastSearchQuery: Query? = null

        override suspend fun search(query: Query, pageToken: String?): Page<Post> {
            lastSearchQuery = query
            if (error != null) throw error
            return Page(items = posts, nextPageToken = null)
        }

        override suspend fun trendingTags(limit: Int): List<TagSuggestion> {
            return emptyList()
        }

        override suspend fun autocompleteTags(prefix: String, limit: Int): List<TagSuggestion> {
            return emptyList()
        }

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

        override suspend fun resolvePost(id: PostId): Post? {
            return posts.firstOrNull { it.id == id }
        }
    }
}
