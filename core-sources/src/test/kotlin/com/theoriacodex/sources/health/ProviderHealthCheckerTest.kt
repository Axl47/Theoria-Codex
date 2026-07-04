package com.theoriacodex.sources.health

import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.adapter.QuickQueryKind
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
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
import com.theoriacodex.domain.orchestration.UnifiedSearchOrchestrator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderHealthCheckerTest {
    @Test
    fun `check all reports ok degraded failed and skipped statuses`() = runTest {
        val registry = FakeRegistry(
            mapOf(
                SourceKey.PIXIV to FakeAdapter(SourceKey.PIXIV, Page(items = listOf(samplePost(SourceKey.PIXIV)), nextPageToken = null)),
                SourceKey.GELBOORU to FakeAdapter(SourceKey.GELBOORU, Page(items = emptyList(), nextPageToken = null)),
                SourceKey.AIBOORU to FakeAdapter(
                    SourceKey.AIBOORU,
                    failure = SourceAdapterException(SourceFailureReason.RATE_LIMITED, "slow down"),
                ),
            )
        )

        val results = ProviderHealthChecker(registry, nowProvider = { 123L }).checkAll(
            listOf(SourceKey.PIXIV, SourceKey.GELBOORU, SourceKey.AIBOORU, SourceKey.NHENTAI),
        ).associateBy { it.source }

        assertEquals(ProviderHealthStatus.OK, results.getValue(SourceKey.PIXIV).status)
        assertEquals(ProviderHealthStatus.DEGRADED, results.getValue(SourceKey.GELBOORU).status)
        assertEquals(ProviderHealthStatus.FAILED, results.getValue(SourceKey.AIBOORU).status)
        assertEquals(SourceFailureReason.RATE_LIMITED, results.getValue(SourceKey.AIBOORU).failureReason)
        assertEquals(ProviderHealthStatus.SKIPPED, results.getValue(SourceKey.NHENTAI).status)
        assertEquals(123L, results.getValue(SourceKey.PIXIV).checkedAtEpochMs)
    }

    @Test
    fun `probe cases parse json configuration`() {
        val cases = ProviderProbeCases.fromJson(
            """
            [
              {
                "source": "GELBOORU",
                "includeTags": ["landscape"],
                "sort": "TOP",
                "autocompletePrefix": "land",
                "strictTagEcho": true,
                "mediaProbe": false
              }
            ]
            """.trimIndent(),
        )

        assertEquals(1, cases.size)
        assertEquals(SourceKey.GELBOORU, cases.single().source)
        assertEquals(listOf("landscape"), cases.single().includeTags)
        assertEquals(SortMode.TOP, cases.single().sort)
        assertEquals("land", cases.single().autocompletePrefix)
        assertEquals(true, cases.single().strictTagEcho)
        assertEquals(false, cases.single().mediaProbe)
    }

    @Test
    fun `probe runner reports seeded autocomplete trending resolve and media steps`() = runTest {
        val post = samplePost(SourceKey.GELBOORU)
        val registry = FakeRegistry(
            mapOf(
                SourceKey.GELBOORU to FakeAdapter(
                    sourceKey = SourceKey.GELBOORU,
                    page = Page(items = listOf(post), nextPageToken = null),
                    trending = listOf(TagSuggestion(text = "landscape", type = "trending", count = 100)),
                    autocomplete = listOf(TagSuggestion(text = "landscape", type = "tag", count = 100)),
                    resolvedPost = post,
                )
            )
        )

        val results = ProviderProbeRunner(registry, nowProvider = { 456L }).runAll(
            listOf(
                ProviderProbeCase(
                    source = SourceKey.GELBOORU,
                    includeTags = listOf("landscape"),
                    autocompletePrefix = "land",
                    strictTagEcho = true,
                )
            )
        ).associateBy { it.checkName }

        assertEquals(ProviderHealthStatus.OK, results.getValue("newest-search").status)
        assertEquals(ProviderHealthStatus.OK, results.getValue("seeded-search").status)
        assertEquals(ProviderHealthStatus.OK, results.getValue("autocomplete-tags").status)
        assertEquals(ProviderHealthStatus.OK, results.getValue("trending-tags").status)
        assertEquals(ProviderHealthStatus.OK, results.getValue("resolve-post").status)
        assertEquals(ProviderHealthStatus.OK, results.getValue("media-metadata").status)
        assertEquals("1", results.getValue("seeded-search").samplePostId)
        assertTrue(results.getValue("media-metadata").message.orEmpty().contains("media URLs"))
    }

    @Test
    fun `probe runner skips credential gated cases without credentials`() = runTest {
        val registry = FakeRegistry(
            mapOf(
                SourceKey.PIXIV to FakeAdapter(
                    sourceKey = SourceKey.PIXIV,
                    page = Page(items = listOf(samplePost(SourceKey.PIXIV)), nextPageToken = null),
                )
            )
        )

        val results = ProviderProbeRunner(registry).runAll(
            listOf(
                ProviderProbeCase(
                    source = SourceKey.PIXIV,
                    includeTags = listOf("landscape"),
                    requiresCredentials = true,
                )
            )
        )

        assertEquals(1, results.size)
        assertEquals("credentials", results.single().checkName)
        assertEquals(ProviderHealthStatus.SKIPPED, results.single().status)
    }

    private class FakeRegistry(
        private val adapters: Map<SourceKey, SourceAdapter>,
    ) : SourceAdapterRegistry {
        override fun availableSources(): Set<SourceKey> = adapters.keys
        override fun adapterFor(sourceKey: SourceKey): SourceAdapter? = adapters[sourceKey]
        override fun unifiedOrchestrator(): UnifiedSearchOrchestrator = UnifiedSearchOrchestrator(adapters)
    }

    private class FakeAdapter(
        override val sourceKey: SourceKey,
        private val page: Page<Post> = Page(emptyList(), null),
        private val failure: Throwable? = null,
        private val trending: List<TagSuggestion> = emptyList(),
        private val autocomplete: List<TagSuggestion> = emptyList(),
        private val resolvedPost: Post? = null,
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

        override suspend fun search(query: Query, pageToken: String?): Page<Post> {
            failure?.let { throw it }
            return page
        }

        override suspend fun trendingTags(limit: Int): List<TagSuggestion> = trending.take(limit)
        override suspend fun autocompleteTags(prefix: String, limit: Int): List<TagSuggestion> = autocomplete.take(limit)
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

        override suspend fun resolvePost(id: PostId): Post? = resolvedPost?.takeIf { it.id == id }
    }

    private fun samplePost(source: SourceKey): Post {
        return Post(
            id = PostId(source, "1"),
            preview = ImageRef(url = "https://example.com/1.jpg", localPath = null, mime = "image/jpeg"),
            full = null,
            pageUrl = null,
            width = null,
            height = null,
            canonicalTags = listOf("landscape"),
            rawTags = listOf("landscape"),
            authorName = null,
            createdAtEpochMs = null,
        )
    }
}
