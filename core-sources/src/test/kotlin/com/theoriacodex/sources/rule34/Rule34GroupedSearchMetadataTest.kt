package com.theoriacodex.sources.rule34

import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SearchTermGroup
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.orchestration.UnifiedSearchOrchestrator
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.http.SourceHttpResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Rule34GroupedSearchMetadataTest {
    @Test
    fun `video providers verify multiple groups using real detail metadata and preserve continuation`() = runTest {
        for (gen in listOf(false, true)) {
            val http = VideoHttpClient(gen)
            val adapter = adapter(http, gen)
            val orchestrator = UnifiedSearchOrchestrator(emptyMap())

            val first = orchestrator.searchSource(adapter, groupedQuery(), null)

            assertEquals(listOf("42", "44"), first.items.map { it.id.sourcePostId })
            assertEquals(listOf("cat", "red"), first.items.first().canonicalTags)
            assertEquals(setOf("42", "43", "44"), http.resolvedIds.toSet())
            assertEquals(3, http.resolvedIds.size)
            assertNotNull(first.nextPageToken)

            val second = orchestrator.searchSource(adapter, groupedQuery(), first.nextPageToken)

            assertTrue(second.items.isEmpty())
            assertEquals(null, second.nextPageToken)
            assertEquals(3, http.resolvedIds.size)
            assertEquals(4, http.searchUrls.size)
            assertTrue(http.searchUrls.takeLast(2).all { "2" in it })
        }
    }

    @Test
    fun `ordinary search cards do not invent query tags or request details`() = runTest {
        for (gen in listOf(false, true)) {
            val http = VideoHttpClient(gen)
            val adapter = adapter(http, gen)
            val query = plainQuery().copy(includeTerms = listOf(SearchTerm("cat")))

            val page = UnifiedSearchOrchestrator(emptyMap()).searchSource(adapter, query, null)

            assertEquals(2, page.items.size)
            assertTrue(page.items.all { it.canonicalTags.isEmpty() && it.rawTags.isEmpty() && it.taxonomy.isEmpty() })
            assertTrue(http.resolvedIds.isEmpty())
        }
    }

    @Test
    fun `unified local exclusions reuse hydrated details and remove matching actual tags`() = runTest {
        for (gen in listOf(false, true)) {
            val http = VideoHttpClient(gen)
            val adapter = adapter(http, gen)
            val query = groupedQuery().copy(excludeTerms = listOf(SearchTerm("blue")))

            val result = UnifiedSearchOrchestrator(mapOf(adapter.sourceKey to adapter))
                .search(query, setOf(adapter.sourceKey), emptyMap(), emptyMap())

            assertEquals(listOf("42"), result.items.map { it.id.sourcePostId })
            assertEquals(3, http.resolvedIds.size)
            assertNotNull(result.nextPageTokens[adapter.sourceKey])
        }
    }

    @Test
    fun `unified exclusions hydrate sparse cards even without alternative groups`() = runTest {
        val http = VideoHttpClient(gen = false)
        val adapter = adapter(http, gen = false)
        val query = plainQuery().copy(includeTerms = listOf(SearchTerm("cat")), excludeTerms = listOf(SearchTerm("green")))

        val result = UnifiedSearchOrchestrator(mapOf(adapter.sourceKey to adapter))
            .search(query, setOf(adapter.sourceKey), emptyMap(), emptyMap())

        assertEquals(listOf("42"), result.items.map { it.id.sourcePostId })
        assertEquals(listOf("42", "43"), http.resolvedIds)
    }

    private fun adapter(http: SourceHttpClient, gen: Boolean): AbstractRule34KvsVideoSourceAdapter =
        if (gen) Rule34GenSourceAdapter(http) else Rule34VideoSourceAdapter(http)

    private fun plainQuery() = Query(
        mode = QueryMode.Unified, includeTerms = emptyList(), excludeTerms = emptyList(),
        sort = SortMode.NEWEST, dateRange = null, minScore = null,
    )

    private fun groupedQuery() = plainQuery().withIncludeTermGroups(listOf(
        SearchTermGroup(listOf(SearchTerm("cat"), SearchTerm("dog"))),
        SearchTermGroup(listOf(SearchTerm("red"), SearchTerm("blue"))),
    ))

    private class VideoHttpClient(gen: Boolean) : SourceHttpClient {
        val searchUrls = mutableListOf<String>()
        val resolvedIds = mutableListOf<String>()
        private val base = if (gen) "https://rule34gen.com" else "https://rule34video.com"

        override suspend fun get(url: String, query: Map<String, String>, headers: Map<String, String>): SourceHttpResponse {
            val body = if ("/search/" in url) {
                searchUrls += url
                if (url.contains('?') || url.endsWith("/2/")) "<html><body></body></html>" else {
                    val term = url.substringAfter("/search/").trimEnd('/')
                    searchPage(term, if (term == "cat") listOf("42", "43") else listOf("43", "44"))
                }
            } else {
                val id = url.substringAfter("/video/").substringBefore('/')
                resolvedIds += id
                val tags = when (id) {
                    "42" -> "cat, red"
                    "43" -> "cat, dog, green"
                    "44" -> "dog, blue"
                    else -> error("Unexpected detail request: $url")
                }
                detailPage(id, tags)
            }
            return SourceHttpResponse(200, body)
        }

        override suspend fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): SourceHttpResponse =
            error("Unexpected POST: $url")

        private fun searchPage(term: String, ids: List<String>): String = """
            <html><body>
            ${ids.joinToString("") { id -> """
                <div class="item thumb"><a class="th" href="$base/video/$id/title/" title="Post $id">
                    <img src="$base/$id.jpg" />
                </a></div>
            """ }}
            <div data-block-next="$base/search/$term/2/"></div>
            <div class="pager next"><a data-block-id="videos" data-parameters="from_videos:2"></a></div>
            </body></html>
        """

        private fun detailPage(id: String, tags: String): String = """
            <html><body><script>
            var config = { video_url: '$base/$id.mp4', video_tags: '$tags' };
            kt_player('player', config);
            </script></body></html>
        """
    }
}
