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
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SearchTermGroup
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.query.CapabilityExclusionReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedSearchOrchestratorTest {
    @Test
    fun `fallback branches one OR group filters canonically and retains branch continuation`() = runTest {
        val required = SearchTerm("landscape")
        val cat = SearchTerm("cat")
        val dog = SearchTerm("dog")
        val query = sampleQuery().withIncludeTermGroups(
            listOf(SearchTermGroup.single(required), SearchTermGroup(listOf(cat, dog))),
        )
        val adapter = BranchingAdapter()
        val orchestrator = UnifiedSearchOrchestrator(mapOf(SourceKey.PIXIV to adapter))

        val first = orchestrator.searchSource(adapter, query, null)
        val second = orchestrator.searchSource(adapter, query, first.nextPageToken)

        assertEquals(listOf("cat-1", "dog-1"), first.items.map { it.id.sourcePostId })
        assertTrue(first.nextPageToken?.startsWith("theoria-group-v1:") == true)
        assertEquals(listOf("cat-2"), second.items.map { it.id.sourcePostId })
        assertEquals(null, second.nextPageToken)
        assertEquals(
            listOf(setOf("landscape", "cat"), setOf("landscape", "dog"), setOf("landscape", "cat")),
            adapter.calls.map { call -> call.first.includeTags.toSet() },
        )
        assertEquals(listOf(null, null, "cat-next"), adapter.calls.map { call -> call.second })
    }

    @Test
    fun `excludes source when capability does not support selected sort`() = runTest {
        val query = Query(
            mode = QueryMode.Unified,
            includeTerms = listOf(SearchTerm("landscape")),
            excludeTerms = emptyList(),
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
    fun `explicit zero weight is not replaced by an equal share`() = runTest {
        val orchestrator = UnifiedSearchOrchestrator(
            adaptersBySource = mapOf(
                SourceKey.PIXIV to FakeAdapter(
                    sourceKey = SourceKey.PIXIV,
                    capabilities = supportedCapabilities(),
                    posts = listOf(post(SourceKey.PIXIV, "p1"), post(SourceKey.PIXIV, "p2")),
                ),
                SourceKey.GELBOORU to FakeAdapter(
                    sourceKey = SourceKey.GELBOORU,
                    capabilities = supportedCapabilities(),
                    posts = listOf(post(SourceKey.GELBOORU, "g1"), post(SourceKey.GELBOORU, "g2")),
                ),
            ),
        )

        val result = orchestrator.search(
            query = sampleQuery(),
            enabledSources = setOf(SourceKey.PIXIV, SourceKey.GELBOORU),
            pageTokens = emptyMap(),
            weights = mapOf(SourceKey.PIXIV to 1.0, SourceKey.GELBOORU to 0.0),
        )

        assertEquals(
            listOf("p1", "p2", "g1", "g2"),
            result.items.map { post -> post.id.sourcePostId },
        )
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
        val override = query.copy(includeTerms = listOf(SearchTerm("gelbooru_tag")))

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
    fun `unified fanout sends only portable general tags to source adapters`() = runTest {
        val adapter = FakeAdapter(
            sourceKey = SourceKey.GELBOORU,
            capabilities = supportedCapabilities(),
            posts = listOf(post(SourceKey.GELBOORU, "g1")),
        )
        val orchestrator = UnifiedSearchOrchestrator(
            adaptersBySource = mapOf(SourceKey.GELBOORU to adapter),
        )
        val query = Query(
            mode = QueryMode.Unified,
            includeTerms = listOf(
                SearchTerm("landscape"),
                SearchTerm("najar", SearchFacet.ARTIST, sourceNamespace = "artist"),
                SearchTerm("x-ray", SearchFacet.TAG, sourceNamespace = "female"),
            ),
            excludeTerms = listOf(
                SearchTerm("lowres"),
                SearchTerm("rei", SearchFacet.CHARACTER, sourceNamespace = "character"),
            ),
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = null,
        )

        orchestrator.search(
            query = query,
            enabledSources = setOf(SourceKey.GELBOORU),
            pageTokens = emptyMap(),
            weights = mapOf(SourceKey.GELBOORU to 1.0),
        )

        assertEquals(listOf(SearchTerm("landscape")), adapter.lastSearchQuery?.includeTerms)
        assertEquals(listOf(SearchTerm("lowres")), adapter.lastSearchQuery?.excludeTerms)
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
            includeTerms = listOf(SearchTerm("landscape")),
            excludeTerms = listOf(SearchTerm("nsfw")),
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

    @Test
    fun `propagates source cancellation instead of publishing a failed source`() = runTest {
        val expected = CancellationException("search superseded")
        val orchestrator = UnifiedSearchOrchestrator(
            adaptersBySource = mapOf(
                SourceKey.PIXIV to FakeAdapter(
                    sourceKey = SourceKey.PIXIV,
                    capabilities = supportedCapabilities(),
                    posts = emptyList(),
                    error = expected,
                ),
            ),
        )

        var thrown: CancellationException? = null
        try {
            orchestrator.search(
                query = sampleQuery(),
                enabledSources = setOf(SourceKey.PIXIV),
                pageTokens = emptyMap(),
                weights = mapOf(SourceKey.PIXIV to 1.0),
            )
        } catch (error: CancellationException) {
            thrown = error
        }

        assertEquals(expected.message, thrown?.message)
    }

    private fun sampleQuery(): Query {
        return Query(
            mode = QueryMode.Unified,
            includeTerms = listOf(SearchTerm("landscape")),
            excludeTerms = emptyList(),
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
                includeTerms = emptyList(),
                excludeTerms = emptyList(),
                sort = SortMode.NEWEST,
                dateRange = null,
                minScore = null,
            )
        }

        override suspend fun resolvePost(id: PostId): Post? {
            return posts.firstOrNull { it.id == id }
        }
    }

    private inner class BranchingAdapter : SourceAdapter {
        override val sourceKey: SourceKey = SourceKey.PIXIV
        override val capabilities: SourceCapabilities = supportedCapabilities()
        val calls = mutableListOf<Pair<Query, String?>>()

        override suspend fun search(query: Query, pageToken: String?): Page<Post> {
            calls += query to pageToken
            return when {
                "cat" in query.includeTags && pageToken == null -> Page(
                    items = listOf(post(SourceKey.PIXIV, "cat-1", listOf("landscape", "cat"))),
                    nextPageToken = "cat-next",
                )
                "cat" in query.includeTags -> Page(
                    items = listOf(post(SourceKey.PIXIV, "cat-2", listOf("landscape", "cat"))),
                    nextPageToken = null,
                )
                else -> Page(
                    items = listOf(
                        post(SourceKey.PIXIV, "dog-1", listOf("landscape", "dog")),
                        post(SourceKey.PIXIV, "not-a-match", listOf("portrait")),
                    ),
                    nextPageToken = null,
                )
            }
        }

        override suspend fun trendingTags(limit: Int): List<TagSuggestion> = emptyList()
        override suspend fun autocompleteTags(prefix: String, limit: Int): List<TagSuggestion> = emptyList()
        override suspend fun quickQuery(kind: QuickQueryKind): Query = sampleQuery()
        override suspend fun resolvePost(id: PostId): Post? = null
    }
}
