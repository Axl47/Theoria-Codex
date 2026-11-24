package com.theoriacodex.domain.orchestration

import com.theoriacodex.domain.adapter.AnimatedSearchTerm
import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceCapabilities
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.MediaFilter
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
    fun `multiple required groups keep an entirely filtered page resumable without restarting exhausted branches (ADR 0011)`() = runTest {
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
        val orchestrator = testOrchestrator()
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
        val orchestrator = testOrchestrator()
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
        val result = testOrchestrator(mapOf(SourceKey.PIXIV to failed, SourceKey.GELBOORU to healthy))
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
            published = testOrchestrator().searchSource(adapter, groupedQuery(), null)
        }
        waiting.await()
        job.cancelAndJoin()
        assertTrue(cleanedUp)
        assertNull(published)
    }

    @Test
    fun `a source without grouped search accepts at most eight alternatives in a group (ADR 0011)`() = runTest {
        val adapter = adapter { _, _ -> Page(emptyList(), null) }
        fun alternatives(count: Int) =
            groupedQuery().withIncludeTermGroups(listOf(SearchTermGroup((1..count).map { SearchTerm("tag$it") })))

        testOrchestrator().searchSource(adapter, alternatives(8), null)
        val rejected = runCatchingPreservingCancellation { testOrchestrator().searchSource(adapter, alternatives(9), null) }

        assertTrue("nine alternatives were searched", rejected.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `a source that groups server-side gets the query unchanged (ADR 0011)`() = runTest {
        val received = mutableListOf<Query>()
        val adapter = adapter(sourceCapabilities = capabilities.copy(supportsGroupedIncludeTagsServerSide = true)) { query, _ ->
            received += query
            Page(emptyList(), null)
        }

        testOrchestrator().searchSource(adapter, groupedQuery(), null)

        assertEquals(listOf(groupedQuery()), received)
    }

    @Test
    fun `the animated term reaches the provider but not the local match or the reported query (ADR 0011)`() = runTest {
        val requested = mutableListOf<List<String>>()
        val animated = capabilities.copy(animatedTerm = AnimatedSearchTerm(SearchTerm("animated")))
        val adapter = adapter(sourceCapabilities = animated) { query, _ ->
            requested += query.includeTags
            val alternative = query.includeTags.single { it == "cat" || it == "dog" }
            Page(listOf(post(alternative, alternative, "red")), null)
        }

        val result = testOrchestrator(mapOf(adapter.sourceKey to adapter))
            .search(groupedQuery().copy(media = MediaFilter.ANIMATED), setOf(adapter.sourceKey), emptyMap(), emptyMap())

        assertEquals(listOf(true, true), requested.map { "animated" in it })
        assertEquals(setOf("cat", "dog"), result.items.map { it.id.sourcePostId }.toSet())
        assertEquals(false, "animated" in result.queriesBySource.getValue(adapter.sourceKey).includeTags)
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

    @Test
    fun `a source without grouped search branches on the smallest or group (ADR 0011)`() = runTest {
        val requests = mutableListOf<List<String>>()
        val adapter = adapter { query, _ ->
            requests += query.includeTags
            Page(emptyList(), null)
        }
        val query = groupedQuery().withIncludeTermGroups(listOf(
            SearchTermGroup.single(SearchTerm("forest")),
            SearchTermGroup((1..9).map { SearchTerm("tag$it") }),
            SearchTermGroup(listOf(SearchTerm("cat"), SearchTerm("dog"))),
        ))

        testOrchestrator().searchSource(adapter, query, null)

        assertEquals(listOf(listOf("forest", "cat"), listOf("forest", "dog")), requests)
    }

    private val capabilities = SourceCapabilities(
        supportsSortNewest = true, supportsSortPopular = true, supportsSortTop = true,
        supportsSortRandom = true, supportsExcludeTagsServerSide = true,
    )

    private fun adapter(
        source: SourceKey = SourceKey.PIXIV,
        sourceCapabilities: SourceCapabilities = capabilities,
        load: suspend (Query, String?) -> Page<Post>,
    ) = object : SourceAdapter {
        override val sourceKey = source
        override val capabilities = sourceCapabilities
        override suspend fun search(query: Query, pageToken: String?) = load(query, pageToken)
        override suspend fun trendingTags(limit: Int): List<TagSuggestion> = emptyList()
        override suspend fun autocompleteTags(prefix: String, limit: Int): List<TagSuggestion> = emptyList()
        override suspend fun resolvePost(id: PostId): Post? = error("Unexpected resolution")
    }
}
