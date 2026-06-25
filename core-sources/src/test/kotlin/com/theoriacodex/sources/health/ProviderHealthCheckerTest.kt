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

        override suspend fun trendingTags(limit: Int): List<TagSuggestion> = emptyList()
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

        override suspend fun resolvePost(id: PostId): Post? = null
    }

    private fun samplePost(source: SourceKey): Post {
        return Post(
            id = PostId(source, "1"),
            preview = ImageRef(url = "https://example.com/1.jpg", localPath = null, mime = "image/jpeg"),
            full = null,
            pageUrl = null,
            width = null,
            height = null,
            canonicalTags = listOf("tag"),
            rawTags = listOf("tag"),
            authorName = null,
            createdAtEpochMs = null,
        )
    }
}
