package com.theoriacodex.sources.health

import com.theoriacodex.domain.adapter.FacetedSearchScope
import com.theoriacodex.domain.adapter.FacetedSearchSourceAdapter
import com.theoriacodex.domain.adapter.FacetedTagSuggestion
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
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.UnifiedSearchOrchestrator
import kotlinx.coroutines.CancellationException
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
                "includeTerms": [
                  {"value": "najar", "facet": "ARTIST", "sourceNamespace": "artist"}
                ],
                "requiredAnyTagGroups": [["landscape", "cityscape"]],
                "sort": "TOP",
                "autocompletePrefix": "land",
                "autocompleteProbes": [
                  {"prefix": "kio", "checkName": "autocomplete-artist", "facet": "ARTIST", "sourceNamespace": "artist"}
                ],
                "strictTagEcho": true,
                "mediaProbe": false,
                "trendingProbe": false
              }
            ]
            """.trimIndent(),
        )

        assertEquals(1, cases.size)
        assertEquals(SourceKey.GELBOORU, cases.single().source)
        assertEquals(listOf("landscape"), cases.single().includeTags)
        assertEquals(listOf(SearchTerm("najar", SearchFacet.ARTIST, "artist")), cases.single().includeTerms)
        assertEquals(listOf(listOf("landscape", "cityscape")), cases.single().requiredAnyTagGroups)
        assertEquals(SortMode.TOP, cases.single().sort)
        assertEquals("land", cases.single().autocompletePrefix)
        assertEquals(
            listOf(ProviderAutocompleteProbe("kio", "autocomplete-artist", SearchFacet.ARTIST, "artist")),
            cases.single().autocompleteProbes,
        )
        assertEquals(true, cases.single().strictTagEcho)
        assertEquals(false, cases.single().mediaProbe)
        assertEquals(false, cases.single().trendingProbe)
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
    fun `probe runner verifies every seeded post against diagnostic OR groups`() = runTest {
        val matching = samplePost(SourceKey.GELBOORU)
        val nonMatching = samplePost(SourceKey.GELBOORU).copy(
            id = PostId(SourceKey.GELBOORU, "2"),
            canonicalTags = listOf("portrait"),
            rawTags = listOf("portrait"),
        )
        val registry = FakeRegistry(
            mapOf(
                SourceKey.GELBOORU to FakeAdapter(
                    sourceKey = SourceKey.GELBOORU,
                    page = Page(items = listOf(matching, nonMatching), nextPageToken = null),
                )
            )
        )

        val result = ProviderProbeRunner(registry).runAll(
            listOf(
                ProviderProbeCase(
                    source = SourceKey.GELBOORU,
                    includeTags = listOf("{landscape ~ cityscape}"),
                    requiredAnyTagGroups = listOf(listOf("landscape", "cityscape")),
                    mediaProbe = false,
                    trendingProbe = false,
                )
            )
        ).first { it.checkName == "seeded-search" }

        assertEquals(ProviderHealthStatus.DEGRADED, result.status)
        assertTrue(result.message.orEmpty().contains("1/2 posts"))
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

    @Test
    fun `default Hitomi probe preserves typed artist search and skips unsupported trending`() = runTest {
        val post = samplePost(SourceKey.HITOMI)
        val adapter = FakeAdapter(
            sourceKey = SourceKey.HITOMI,
            page = Page(items = listOf(post), nextPageToken = null),
            autocomplete = listOf(
                TagSuggestion(text = "tag", type = "tag", count = 10),
                TagSuggestion(text = "kio artist", type = "artist", count = 5),
            ),
            resolvedPost = post,
        )
        val probeCase = ProviderProbeCases.defaults.single { it.source == SourceKey.HITOMI }

        val results = ProviderProbeRunner(FakeRegistry(mapOf(SourceKey.HITOMI to adapter))).runAll(
            listOf(probeCase),
        )

        assertEquals(
            listOf(SearchTerm("najar", SearchFacet.ARTIST, "artist")),
            adapter.capturedQueries[1].includeTerms,
        )
        assertTrue(results.none { result -> result.checkName == "trending-tags" })
        assertEquals(
            listOf(
                FacetedSearchScope.All,
                FacetedSearchScope(SearchFacet.ARTIST, "artist"),
            ),
            adapter.capturedAutocompleteScopes,
        )
        assertEquals(
            setOf("autocomplete-global", "autocomplete-artist"),
            results.map(ProviderProbeStepResult::checkName)
                .filter { name -> name.startsWith("autocomplete-") }
                .toSet(),
        )
        assertEquals(
            probeCase.diagnosticUrls.getValue("seeded-search"),
            results.single { result -> result.checkName == "seeded-search" }.requestUrl,
        )
    }

    @Test
    fun `health checker rethrows cancellation instead of reporting provider failure`() = runTest {
        val adapter = FakeAdapter(
            sourceKey = SourceKey.HITOMI,
            failure = CancellationException("cancel checker"),
        )

        val failure = runCatching {
            ProviderHealthChecker(FakeRegistry(mapOf(SourceKey.HITOMI to adapter))).checkAll()
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
    }

    @Test
    fun `probe runner rethrows cancellation instead of converting it to a failed step`() = runTest {
        val adapter = FakeAdapter(
            sourceKey = SourceKey.HITOMI,
            failure = CancellationException("cancel probe"),
        )

        val failure = runCatching {
            ProviderProbeRunner(FakeRegistry(mapOf(SourceKey.HITOMI to adapter))).runAll(
                listOf(
                    ProviderProbeCase(
                        source = SourceKey.HITOMI,
                        includeTerms = listOf(SearchTerm("najar", SearchFacet.ARTIST, "artist")),
                        autocompletePrefix = "tag",
                        trendingProbe = false,
                    ),
                ),
            )
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
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
    ) : SourceAdapter, FacetedSearchSourceAdapter {
        val capturedQueries = mutableListOf<Query>()
        val capturedAutocompleteScopes = mutableListOf<FacetedSearchScope>()

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
            capturedQueries += query
            failure?.let { throw it }
            return page
        }

        override suspend fun trendingTags(limit: Int): List<TagSuggestion> = trending.take(limit)
        override suspend fun autocompleteTags(prefix: String, limit: Int): List<TagSuggestion> = autocomplete.take(limit)
        override val supportedSearchScopes: Set<FacetedSearchScope> = setOf(
            FacetedSearchScope.All,
            FacetedSearchScope(SearchFacet.ARTIST, "artist"),
        )

        override suspend fun autocompleteFaceted(
            prefix: String,
            scope: FacetedSearchScope,
            limit: Int,
        ): List<FacetedTagSuggestion> {
            capturedAutocompleteScopes += scope
            return autocomplete.take(limit).map { suggestion ->
                val namespace = suggestion.type
                FacetedTagSuggestion(
                    text = suggestion.text,
                    facet = if (namespace == "artist") SearchFacet.ARTIST else SearchFacet.TAG,
                    sourceNamespace = namespace,
                    count = suggestion.count,
                )
            }
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
