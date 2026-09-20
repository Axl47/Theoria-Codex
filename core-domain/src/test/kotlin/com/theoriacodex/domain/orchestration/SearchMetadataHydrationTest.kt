package com.theoriacodex.domain.orchestration

import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.adapter.QuickQueryKind
import com.theoriacodex.domain.adapter.SearchMetadataSourceAdapter
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
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchMetadataHydrationTest {
    @Test
    fun `group verification hydrates overlapping identities once and deduplicates after canonical resolution`() = runTest {
        val resolved = mutableListOf<String>()
        val adapter = SparseAdapter(
            search = { query, _ ->
                Page(if ("cat" in query.includeTags) listOf(post("a"), post("shared"))
                    else listOf(post("shared"), post("alias"), post("gone")), "next")
            },
            resolve = { sparse ->
                resolved += sparse.id.sourcePostId
                when (sparse.id.sourcePostId) {
                    "a", "alias" -> post("canonical", "cat", "red")
                    "shared" -> post("shared", "dog", "green")
                    else -> null
                }
            },
        )

        val page = UnifiedSearchOrchestrator(emptyMap()).searchSource(adapter, groupedQuery(), null)

        assertEquals(listOf("canonical"), page.items.map { it.id.sourcePostId })
        assertEquals(listOf("a", "shared", "alias", "gone"), resolved)
        assertNotNull(page.nextPageToken)
    }

    @Test
    fun `search without local predicates keeps sparse cards and does not hydrate`() = runTest {
        val sparse = post("a")
        val adapter = SparseAdapter(search = { _, _ -> Page(listOf(sparse), "next") })

        val page = UnifiedSearchOrchestrator(emptyMap()).searchSource(adapter, plainQuery(), null)

        assertEquals(listOf(sparse), page.items)
        assertEquals("next", page.nextPageToken)
    }

    @Test
    fun `metadata deadline cancels at most two active resolutions and preserves another source`() = runTest {
        var active = 0
        var maximum = 0
        val sparse = SparseAdapter(
            search = { _, _ -> Page((1..8).map { post(it.toString()) }, "next") },
            resolve = {
                active += 1
                maximum = maxOf(maximum, active)
                try { awaitCancellation() } finally { active -= 1 }
            },
        )
        val healthy = SparseAdapter(
            sourceKey = SourceKey.GELBOORU,
            search = { _, _ -> Page(listOf(post("healthy", "cat", "red").copy(
                id = PostId(SourceKey.GELBOORU, "healthy"),
            )), null) },
            resolve = { it },
        )
        val result = UnifiedSearchOrchestrator(
            mapOf(sparse.sourceKey to sparse, healthy.sourceKey to healthy), sourceTimeoutMs = 100L,
        ).search(groupedQuery(), setOf(sparse.sourceKey, healthy.sourceKey), emptyMap(), emptyMap())

        assertEquals(2, maximum)
        assertEquals(0, active)
        assertEquals(listOf("healthy"), result.items.map { it.id.sourcePostId })
        assertEquals(SourceFailureReason.NETWORK, result.statuses.single { it.source == sparse.sourceKey }.failureReason)
    }

    @Test
    fun `leaving during metadata acquisition cancels work without publishing a page`() = runTest {
        val started = CompletableDeferred<Unit>()
        var cancelled = false
        var result: Page<Post>? = null
        val adapter = SparseAdapter(
            search = { _, _ -> Page(listOf(post("a")), "next") },
            resolve = {
                started.complete(Unit)
                try { awaitCancellation() } finally { cancelled = true }
            },
        )
        val job = launch {
            result = UnifiedSearchOrchestrator(emptyMap()).searchSource(adapter, groupedQuery(), null)
        }
        started.await()
        job.cancelAndJoin()

        assertTrue(cancelled)
        assertEquals(null, result)
    }

    @Test
    fun `metadata failures remain failures instead of becoming empty successful results`() = runTest {
        val adapter = SparseAdapter(
            search = { _, _ -> Page(listOf(post("a")), "next") },
            resolve = { throw SourceAdapterException(SourceFailureReason.PARSE, "Changed detail page") },
        )
        val result = UnifiedSearchOrchestrator(mapOf(adapter.sourceKey to adapter))
            .search(groupedQuery(), setOf(adapter.sourceKey), emptyMap(), emptyMap())

        assertTrue(result.items.isEmpty())
        assertEquals(SourceRunState.FAILED, result.statuses.single().state)
        assertEquals(SourceFailureReason.PARSE, result.statuses.single().failureReason)
    }

    private class SparseAdapter(
        override val sourceKey: SourceKey = SourceKey.RULE34VIDEO,
        private val search: suspend (Query, String?) -> Page<Post>,
        private val resolve: suspend (Post) -> Post? = { error("Unexpected metadata resolution") },
    ) : SourceAdapter, SearchMetadataSourceAdapter {
        override val capabilities = SourceCapabilities(true, true, true, false, false, false, false, false)
        override suspend fun search(query: Query, pageToken: String?) = search.invoke(query, pageToken)
        override suspend fun resolveSearchMetadata(post: Post) = resolve(post)
        override suspend fun resolvePost(id: PostId): Post? = error("Use metadata capability")
        override suspend fun trendingTags(limit: Int): List<TagSuggestion> = emptyList()
        override suspend fun autocompleteTags(prefix: String, limit: Int): List<TagSuggestion> = emptyList()
        override suspend fun quickQuery(kind: QuickQueryKind): Query = error("Unexpected quick query")
    }

    private fun plainQuery() = Query(
        mode = QueryMode.Unified, includeTerms = emptyList(), excludeTerms = emptyList(),
        sort = SortMode.NEWEST, dateRange = null, minScore = null,
    )

    private fun groupedQuery() = plainQuery().withIncludeTermGroups(listOf(
        SearchTermGroup(listOf(SearchTerm("cat"), SearchTerm("dog"))),
        SearchTermGroup(listOf(SearchTerm("red"), SearchTerm("blue"))),
    ))

    private fun post(id: String, vararg tags: String) = Post(
        id = PostId(SourceKey.RULE34VIDEO, id), preview = ImageRef("https://example.test/$id.jpg", null, "image/jpeg"),
        full = null, pageUrl = null, width = null, height = null, canonicalTags = tags.toList(),
        rawTags = emptyList(), authorName = null, createdAtEpochMs = null,
    )
}
