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
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SearchTermGroup
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupedSearchPagingTest {
    @Test
    fun `multiple required groups keep an entirely filtered page resumable without restarting exhausted branches`() = runTest {
        val requests = mutableListOf<Pair<List<String>, String?>>()
        val adapter = adapter { query, token ->
            requests += query.includeTags to token
            when (query.includeTags.last() to token) {
                "cat" to null -> Page(listOf(post("wrong-color", "cat", "green")), "cat-page-2")
                "dog" to null -> Page(listOf(post("wrong-color-dog", "dog", "green")), null)
                "cat" to "cat-page-2" -> Page(listOf(post("accepted", "cat", "red")), null)
                else -> error("Unexpected branch request ${query.includeTags}: $token")
            }
        }
        val orchestrator = UnifiedSearchOrchestrator(emptyMap())
        val first = orchestrator.searchSource(adapter, groupedQuery(), null)
        assertTrue(first.items.isEmpty())
        assertNotNull(first.nextPageToken)

        val second = orchestrator.searchSource(adapter, groupedQuery(), first.nextPageToken)
        assertEquals(listOf("accepted"), second.items.map { it.id.sourcePostId })
        assertNull(second.nextPageToken)
        assertEquals(
            listOf(listOf("forest", "cat") to null, listOf("forest", "dog") to null,
                listOf("forest", "cat") to "cat-page-2"),
            requests,
        )
    }

    @Test
    fun `overlapping branches preserve selected ordering and publish one canonical identity`() = runTest {
        val shared = post("shared", "cat", "dog", "red", time = 20)
        val cat = post("cat", "cat", "blue", time = 10)
        val dog = post("dog", "dog", "red", time = 30)
        val adapter = adapter { query, _ ->
            Page(if ("cat" in query.includeTags) listOf(shared, cat) else listOf(dog, shared), null)
        }
        val orchestrator = UnifiedSearchOrchestrator(emptyMap())
        val newest = orchestrator.searchSource(adapter, groupedQuery(SortMode.NEWEST), null)
        val popular = orchestrator.searchSource(adapter, groupedQuery(SortMode.POPULAR), null)

        assertEquals(listOf("dog", "shared", "cat"), newest.items.map { it.id.sourcePostId })
        assertEquals(listOf("shared", "dog", "cat"), popular.items.map { it.id.sourcePostId })
        assertNull(newest.nextPageToken)
        assertNull(popular.nextPageToken)
    }

    @Test
    fun `a later branch failure discards partial source results while another source succeeds`() = runTest {
        val failed = adapter { query, _ ->
            if ("dog" in query.includeTags) throw SourceAdapterException(SourceFailureReason.NETWORK, "offline")
            Page(listOf(post("partial", "cat", "red")), "next")
        }
        val healthy = adapter(SourceKey.GELBOORU) { _, _ ->
            Page(listOf(post("healthy", "cat", "red").copy(id = PostId(SourceKey.GELBOORU, "healthy"))), null)
        }
        val result = UnifiedSearchOrchestrator(mapOf(SourceKey.PIXIV to failed, SourceKey.GELBOORU to healthy))
            .search(groupedQuery(), setOf(SourceKey.PIXIV, SourceKey.GELBOORU), emptyMap(), emptyMap())

        assertEquals(listOf("healthy"), result.items.map { it.id.sourcePostId })
        assertEquals(setOf(SourceKey.GELBOORU), result.nextPageTokens.keys)
        assertEquals(SourceFailureReason.NETWORK, result.statuses.single { it.source == SourceKey.PIXIV }.failureReason)
        assertEquals(SourceRunState.SUCCESS, result.statuses.single { it.source == SourceKey.GELBOORU }.state)
    }

    @Test
    fun `leaving during a later branch cancels its work and publishes no partial page`() = runTest {
        val waiting = CompletableDeferred<Unit>()
        var cleanedUp = false
        var published: Page<Post>? = null
        val adapter = adapter { query, _ ->
            if ("dog" in query.includeTags) {
                try {
                    waiting.complete(Unit)
                    awaitCancellation()
                } finally {
                    cleanedUp = true
                }
            }
            Page(listOf(post("partial", "cat", "red")), null)
        }
        val job = launch {
            published = UnifiedSearchOrchestrator(emptyMap()).searchSource(adapter, groupedQuery(), null)
        }
        waiting.await()
        job.cancelAndJoin()
        assertTrue(cleanedUp)
        assertNull(published)
    }

    private fun groupedQuery(sort: SortMode = SortMode.NEWEST) = Query(
        mode = QueryMode.Unified,
        includeTerms = emptyList(), excludeTerms = emptyList(), sort = sort, dateRange = null, minScore = null,
    ).withIncludeTermGroups(
        listOf(
            SearchTermGroup.single(SearchTerm("forest")),
            SearchTermGroup(listOf(SearchTerm("cat"), SearchTerm("dog"))),
            SearchTermGroup(listOf(SearchTerm("red"), SearchTerm("blue"))),
        ),
    )

    private fun post(id: String, vararg tags: String, time: Long = 0) = Post(
        id = PostId(SourceKey.PIXIV, id), preview = ImageRef("https://example.com/$id.jpg", null, "image/jpeg"),
        full = null, pageUrl = null, width = null, height = null, canonicalTags = listOf("forest") + tags,
        rawTags = emptyList(), authorName = null, createdAtEpochMs = time,
    )

    private fun adapter(
        source: SourceKey = SourceKey.PIXIV,
        load: suspend (Query, String?) -> Page<Post>,
    ) = object : SourceAdapter {
        override val sourceKey = source
        override val capabilities = SourceCapabilities(
            supportsSortNewest = true, supportsSortPopular = true, supportsSortTop = true,
            supportsSortRandom = true, supportsExcludeTagsServerSide = true, supportsDateRangeServerSide = true,
            supportsMinScoreServerSide = true, requiresCredentials = false,
        )
        override suspend fun search(query: Query, pageToken: String?) = load(query, pageToken)
        override suspend fun trendingTags(limit: Int): List<TagSuggestion> = emptyList()
        override suspend fun autocompleteTags(prefix: String, limit: Int): List<TagSuggestion> = emptyList()
        override suspend fun quickQuery(kind: QuickQueryKind): Query = error("Unexpected quick query")
        override suspend fun resolvePost(id: PostId): Post? = error("Unexpected resolution")
    }
}
