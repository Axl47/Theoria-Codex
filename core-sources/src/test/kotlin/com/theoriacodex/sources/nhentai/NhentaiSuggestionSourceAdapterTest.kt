package com.theoriacodex.sources.nhentai

import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.adapter.FacetedSearchScope
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.http.SourceHttpResponse
import com.theoriacodex.sources.testing.FakeHttpClient
import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class NhentaiSuggestionSourceAdapterTest : NhentaiSourceAdapterTestFixture() {
    @Test
    fun `autocomplete tags uses v2 tag search endpoint`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextPostResponse = SourceHttpResponse(
                statusCode = 200,
                body = """
                    [
                      {
                        "id": 2937,
                        "type": "tag",
                        "name": "big breasts",
                        "slug": "big-breasts",
                        "url": "/tag/big-breasts/",
                        "count": 224436
                      },
                      {
                        "id": 30555,
                        "type": "tag",
                        "name": "big penis",
                        "slug": "big-penis",
                        "url": "/tag/big-penis/",
                        "count": 32214
                      }
                    ]
                """.trimIndent(),
            )
        }
        val adapter = NhentaiSourceAdapter(httpClient = httpClient)

        val suggestions = adapter.autocompleteTags(prefix = "big", limit = 1)

        assertEquals("https://nhentai.net/api/v2/tags/search", httpClient.lastPost?.url)
        assertEquals("""{"query":"big"}""", httpClient.lastPost?.body)
        assertEquals("application/json", httpClient.lastPost?.headers?.get("Content-Type"))
        assertEquals(listOf("big breasts"), suggestions.map { it.text })
        assertEquals(listOf(224436), suggestions.map { it.count })
    }

    @Test
    fun `faceted autocomplete exposes app scopes and preserves nhentai taxonomy`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextPostResponse = SourceHttpResponse(
                statusCode = 200,
                body = """
                    [
                      {"id": 1, "type": "tag", "name": "najar", "slug": "najar", "count": 10},
                      {"id": 2, "type": "artist", "name": "najar", "slug": "najar", "count": 42},
                      {"id": 3, "type": "parody", "name": "the idolmaster", "slug": "the-idolmaster", "count": 99},
                      {"id": 4, "type": "category", "name": "artistcg", "slug": "artistcg", "count": 7}
                    ]
                """.trimIndent(),
            )
        }
        val adapter = NhentaiSourceAdapter(httpClient = httpClient, minRequestIntervalMs = 0L)

        val suggestions = adapter.autocompleteFaceted(
            prefix = "najar",
            scope = FacetedSearchScope.All,
            limit = 10,
        )
        val tagSuggestions = adapter.autocompleteFaceted(
            prefix = "najar",
            scope = FacetedSearchScope(SearchFacet.TAG, "tag"),
            limit = 10,
        )
        val artistSuggestions = adapter.autocompleteFaceted(
            prefix = "najar",
            scope = FacetedSearchScope(SearchFacet.ARTIST, "artist"),
            limit = 10,
        )

        assertTrue(FacetedSearchScope.All in adapter.supportedSearchScopes)
        assertTrue(FacetedSearchScope(SearchFacet.SERIES, "parody") in adapter.supportedSearchScopes)
        assertTrue(FacetedSearchScope(SearchFacet.TYPE, "category") in adapter.supportedSearchScopes)
        assertEquals(
            listOf(
                Triple(SearchFacet.TAG, "tag", "najar"),
                Triple(SearchFacet.ARTIST, "artist", "najar"),
                Triple(SearchFacet.SERIES, "parody", "the idolmaster"),
                Triple(SearchFacet.TYPE, "category", "artistcg"),
            ),
            suggestions.map { suggestion ->
                Triple(suggestion.facet, suggestion.sourceNamespace, suggestion.text)
            },
        )
        assertEquals(listOf(10, 42, 99, 7), suggestions.map { suggestion -> suggestion.count })
        assertEquals(listOf("najar"), tagSuggestions.map { suggestion -> suggestion.text })
        assertEquals(listOf("najar"), artistSuggestions.map { suggestion -> suggestion.text })
        assertEquals(SearchFacet.TAG, tagSuggestions.single().facet)
        assertEquals(SearchFacet.ARTIST, artistSuggestions.single().facet)
    }

    @Test
    fun `faceted autocomplete filters one provider response to the selected scope`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextPostResponse = SourceHttpResponse(
                statusCode = 200,
                body = """
                    [
                      {"id": 1, "type": "tag", "name": "sample", "slug": "sample", "count": 10},
                      {"id": 2, "type": "artist", "name": "sample artist", "slug": "sample-artist", "count": 42}
                    ]
                """.trimIndent(),
            )
        }
        val adapter = NhentaiSourceAdapter(httpClient = httpClient)

        val suggestions = adapter.autocompleteFaceted(
            prefix = "sample",
            scope = FacetedSearchScope(SearchFacet.ARTIST, "artist"),
            limit = 10,
        )

        assertEquals(listOf("sample artist"), suggestions.map { suggestion -> suggestion.text })
        assertEquals("""{"query":"sample"}""", httpClient.lastPost?.body)
    }

    @Test
    fun `same-name faceted cache resolves a portable search with the tag id`() = runTest {
        val httpClient = QueueHttpClient(
            SourceHttpResponse(
                statusCode = 200,
                body = """
                    [
                      {"id": 11, "type": "tag", "name": "najar", "slug": "najar", "count": 10},
                      {"id": 22, "type": "artist", "name": "najar", "slug": "najar", "count": 42}
                    ]
                """.trimIndent(),
            ),
            SourceHttpResponse(
                statusCode = 200,
                body = """{"result":[],"num_pages":1,"per_page":25}""",
            ),
        )
        val adapter = NhentaiSourceAdapter(httpClient = httpClient, minRequestIntervalMs = 0L)

        adapter.autocompleteFaceted(
            prefix = "najar",
            scope = FacetedSearchScope.All,
            limit = 10,
        )
        adapter.search(
            Query(
                mode = QueryMode.Source(SourceKey.NHENTAI),
                includeTags = listOf("najar"),
                excludeTags = emptyList(),
                sort = SortMode.NEWEST,
                dateRange = null,
                minScore = null,
            ),
            pageToken = null,
        )

        assertEquals(1, httpClient.postRequests.size)
        assertEquals("https://nhentai.net/api/v2/galleries/tagged", httpClient.requests.single().url)
        assertEquals("11", httpClient.requests.single().query["tag_id"])
    }

    @Test
    fun `trending tags samples gallery details to rank tag objects`() = runTest {
        val httpClient = QueueHttpClient(
            SourceHttpResponse(
                statusCode = 200,
                body = """
                    {
                      "result": [
                        {"id": 101, "tag_ids": [1, 2]},
                        {"id": 102, "tag_ids": [1, 3]}
                      ],
                      "num_pages": 1
                    }
                """.trimIndent(),
            ),
            SourceHttpResponse(
                statusCode = 200,
                body = """
                    {
                      "id": 101,
                      "tags": [
                        {"id": 1, "type": "language", "name": "english", "slug": "english", "count": 141927},
                        {"id": 2, "type": "tag", "name": "big breasts", "slug": "big-breasts", "count": 224436}
                      ]
                    }
                """.trimIndent(),
            ),
            SourceHttpResponse(
                statusCode = 200,
                body = """
                    {
                      "id": 102,
                      "tags": [
                        {"id": 1, "type": "language", "name": "english", "slug": "english", "count": 141927},
                        {"id": 3, "type": "category", "name": "doujinshi", "slug": "doujinshi", "count": 490863}
                      ]
                    }
                """.trimIndent(),
            ),
        )
        val adapter = NhentaiSourceAdapter(httpClient = httpClient, minRequestIntervalMs = 0L)

        val suggestions = adapter.trendingTags(limit = 3)

        assertEquals(
            listOf(
                "https://nhentai.net/api/v2/galleries",
                "https://nhentai.net/api/v2/galleries/101",
                "https://nhentai.net/api/v2/galleries/102",
            ),
            httpClient.requests.map { it.url },
        )
        assertEquals(listOf("doujinshi", "big breasts", "english"), suggestions.map { it.text })
    }

}
