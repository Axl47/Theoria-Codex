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

internal class NhentaiSearchSourceAdapterTest : NhentaiSourceAdapterTestFixture() {
    @Test
    fun `search uses galleries search endpoint and query sort params`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = """
                    {
                      "result": [],
                      "num_pages": 1,
                      "per_page": 25
                    }
                """.trimIndent(),
            )
        }
        val adapter = NhentaiSourceAdapter(httpClient = httpClient)

        adapter.search(
            query = Query(
                mode = QueryMode.Source(SourceKey.NHENTAI),
                includeTags = listOf("big_breasts"),
                excludeTags = listOf("loli"),
                sort = SortMode.POPULAR,
                dateRange = null,
                minScore = null,
            ),
            pageToken = null,
        )

        assertEquals("https://nhentai.net/api/v2/search", httpClient.lastGet?.url)
        assertEquals("1", httpClient.lastGet?.query?.get("page"))
        assertEquals("big breasts -loli", httpClient.lastGet?.query?.get("query"))
        assertEquals("popular-today", httpClient.lastGet?.query?.get("sort"))
        assertEquals("TheoriaCodex/1.0 (Android source adapter)", httpClient.lastGet?.headers?.get("User-Agent"))
        assertEquals("https://nhentai.net/", httpClient.lastGet?.headers?.get("Referer"))
    }

    @Test
    fun `search compiles typed app facets to nhentai namespaces`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = """{"result":[],"num_pages":1,"per_page":25}""",
            )
        }
        val adapter = NhentaiSourceAdapter(httpClient = httpClient)
        val query = Query(
            mode = QueryMode.Source(SourceKey.NHENTAI),
            includeTerms = listOf(
                SearchTerm("najar", SearchFacet.ARTIST, "artist"),
                SearchTerm("the idolmaster", SearchFacet.SERIES, "parody"),
                SearchTerm("artistcg", SearchFacet.TYPE, "category"),
                SearchTerm("english", SearchFacet.LANGUAGE, "language"),
                SearchTerm("full color", SearchFacet.TAG, "tag"),
            ),
            excludeTerms = listOf(
                SearchTerm("rin", SearchFacet.CHARACTER, "character"),
            ),
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = null,
        )

        adapter.search(query = query, pageToken = null)

        assertEquals(
            "artist:najar parody:\"the idolmaster\" category:artistcg " +
                "language:english tag:\"full color\" -character:rin",
            httpClient.lastGet?.query?.get("query"),
        )
    }

    @Test
    fun `blank popular search uses v2 wildcard query`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = """
                    {
                      "result": [],
                      "num_pages": 1,
                      "per_page": 25
                    }
                """.trimIndent(),
            )
        }
        val adapter = NhentaiSourceAdapter(httpClient = httpClient)

        adapter.search(
            query = Query(
                mode = QueryMode.Source(SourceKey.NHENTAI),
                includeTags = emptyList(),
                excludeTags = emptyList(),
                sort = SortMode.POPULAR,
                dateRange = null,
                minScore = null,
            ),
            pageToken = null,
        )

        assertEquals("https://nhentai.net/api/v2/search", httpClient.lastGet?.url)
        assertEquals("*", httpClient.lastGet?.query?.get("query"))
        assertEquals("popular-today", httpClient.lastGet?.query?.get("sort"))
    }

    @Test
    fun `search maps gallery pages and next token`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = """
                    {
                      "result": [
                        {
                          "id": 123,
                          "media_id": 9876,
                          "title": {
                            "english": "",
                            "japanese": "",
                            "pretty": "Test Gallery"
                          },
                          "images": {
                            "thumbnail": {"t": "w", "w": 350, "h": 500},
                            "cover": {"t": "w", "w": 700, "h": 1000},
                            "pages": [
                              {"t": "w", "w": 1200, "h": 1800},
                              {"t": "g", "w": 1200, "h": 1800}
                            ]
                          },
                          "scanlator": null,
                          "upload_date": 1710000000,
                          "tags": [
                            {"id": 1, "type": "tag", "name": "big breasts", "count": 5000}
                          ],
                          "num_pages": 2,
                          "num_favorites": 150
                        }
                      ],
                      "num_pages": 2,
                      "per_page": 25
                    }
                """.trimIndent(),
            )
        }
        val adapter = NhentaiSourceAdapter(httpClient = httpClient)

        val page = adapter.search(multiTagQuery(), pageToken = null)

        assertEquals("2", page.nextPageToken)
        val post = page.items.firstOrNull()
        assertNotNull(post)
        assertEquals(SourceKey.NHENTAI, post?.id?.source)
        assertEquals("https://t.nhentai.net/galleries/9876/thumb.webp", post?.preview?.url)
        assertEquals(2, post?.media?.size)
        assertEquals("https://i.nhentai.net/galleries/9876/1.webp", post?.media?.get(0)?.url)
        assertEquals("https://i.nhentai.net/galleries/9876/2.gif", post?.media?.get(1)?.url)
        assertEquals("https://nhentai.net/g/123/", post?.pageUrl)
        assertEquals(listOf("big breasts"), post?.canonicalTags)
        assertEquals(
            listOf(Triple(SearchFacet.TAG, "tag", "big breasts")),
            post?.taxonomy?.map { term -> Triple(term.facet, term.sourceNamespace, term.value) },
        )
        assertEquals(2, post?.mediaCount)
    }

    @Test
    fun `search maps v2 lightweight gallery cards as lazy posts`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = """
                    {
                      "result": [
                        {
                          "id": 659203,
                          "media_id": "4009462",
                          "english_title": "[Osuwaani] Commission",
                          "japanese_title": null,
                          "thumbnail": "galleries/4009462/thumb.webp",
                          "thumbnail_width": 250,
                          "thumbnail_height": 348,
                          "num_pages": 4,
                          "num_favorites": 69,
                          "tag_ids": []
                        }
                      ],
                      "num_pages": 2,
                      "per_page": 25,
                      "total": 26
                    }
                """.trimIndent(),
            )
        }
        val adapter = NhentaiSourceAdapter(httpClient = httpClient)

        val page = adapter.search(multiTagQuery(), pageToken = null)

        assertEquals("2", page.nextPageToken)
        val post = page.items.single()
        assertEquals("659203", post.id.sourcePostId)
        assertEquals("[Osuwaani] Commission", post.title)
        assertEquals("https://t.nhentai.net/galleries/4009462/thumb.webp", post.preview.url)
        assertEquals("image/webp", post.preview.mime)
        assertNull(post.full)
        assertTrue(post.media.isEmpty())
        assertEquals(4, post.mediaCount)
    }

    @Test
    fun `resolve post returns null for not found gallery`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(statusCode = 404, body = "{}")
        }
        val adapter = NhentaiSourceAdapter(httpClient = httpClient)

        val resolved = adapter.resolvePost(PostId(source = SourceKey.NHENTAI, sourcePostId = "123456"))

        assertNull(resolved)
    }

    @Test
    fun `search maps blocked html response to network failure`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = "<html><body>blocked</body></html>",
            )
        }
        val adapter = NhentaiSourceAdapter(httpClient = httpClient)

        val failure = runCatching { adapter.search(multiTagQuery(), pageToken = null) }.exceptionOrNull()

        require(failure is SourceAdapterException)
        assertEquals(SourceFailureReason.NETWORK, failure.reason)
        assertTrue(failure.message?.contains("non-JSON", ignoreCase = true) == true)
    }

    @Test
    fun `non-challenge forbidden response keeps nhentai unknown failure semantics`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(statusCode = 403, body = "forbidden")
        }
        val adapter = NhentaiSourceAdapter(httpClient = httpClient)

        val failure = runCatching { adapter.search(multiTagQuery(), pageToken = null) }.exceptionOrNull()

        require(failure is SourceAdapterException)
        assertEquals(SourceFailureReason.UNKNOWN, failure.reason)
    }

    @Test
    fun `search resolves direct gallery id without search endpoint`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = """
                    {
                      "id": 634609,
                      "media_id": 3822885,
                      "title": {
                        "pretty": "Direct ID"
                      },
                      "images": {
                        "thumbnail": {"t": "w", "w": 250, "h": 360},
                        "cover": {"t": "w", "w": 700, "h": 1000},
                        "pages": [
                          {"t": "w", "w": 1200, "h": 1800}
                        ]
                      },
                      "tags": [],
                      "upload_date": 1710000000
                    }
                """.trimIndent(),
            )
        }
        val adapter = NhentaiSourceAdapter(httpClient = httpClient)

        val page = adapter.search(
            query = Query(
                mode = QueryMode.Source(SourceKey.NHENTAI),
                includeTags = listOf("english", "634609"),
                excludeTags = emptyList(),
                sort = SortMode.NEWEST,
                dateRange = null,
                minScore = null,
            ),
            pageToken = null,
        )

        assertEquals("https://nhentai.net/api/v2/galleries/634609", httpClient.lastGet?.url)
        assertTrue(httpClient.lastGet?.query?.isEmpty() == true)
        assertEquals(1, page.items.size)
        assertEquals("634609", page.items.first().id.sourcePostId)
    }

    @Test
    fun `direct gallery id lookup ignores nhentai filter tags`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = """
                    {
                      "id": 634609,
                      "media_id": 3822885,
                      "title": {"pretty": "Direct ID"},
                      "images": {
                        "thumbnail": {"t": "w"},
                        "cover": {"t": "w"},
                        "pages": [{"t": "w"}]
                      },
                      "tags": []
                    }
                """.trimIndent(),
            )
        }
        val adapter = NhentaiSourceAdapter(httpClient = httpClient)

        val page = adapter.search(
            query = Query(
                mode = QueryMode.Source(SourceKey.NHENTAI),
                includeTags = listOf("english", "full color", "634609"),
                excludeTags = emptyList(),
                sort = SortMode.NEWEST,
                dateRange = null,
                minScore = null,
            ),
            pageToken = null,
        )

        assertEquals("https://nhentai.net/api/v2/galleries/634609", httpClient.lastGet?.url)
        assertEquals(1, page.items.size)
    }

    @Test
    fun `single tag search resolves tag id and uses tagged galleries endpoint`() = runTest {
        val httpClient = QueueHttpClient(
            SourceHttpResponse(
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
                      }
                    ]
                """.trimIndent(),
            ),
            SourceHttpResponse(
                statusCode = 200,
                body = """
                    {
                      "result": [],
                      "num_pages": 1,
                      "per_page": 25
                    }
                """.trimIndent(),
            ),
        )
        val adapter = NhentaiSourceAdapter(httpClient = httpClient)

        adapter.search(sampleQuery(), pageToken = null)

        assertEquals("https://nhentai.net/api/v2/tags/search", httpClient.postRequests.single().url)
        assertEquals("""{"query":"big breasts"}""", httpClient.postRequests.single().body)
        assertEquals("https://nhentai.net/api/v2/galleries/tagged", httpClient.requests.single().url)
        assertEquals("2937", httpClient.requests.single().query["tag_id"])
        assertEquals("date", httpClient.requests.single().query["sort"])
    }

}
