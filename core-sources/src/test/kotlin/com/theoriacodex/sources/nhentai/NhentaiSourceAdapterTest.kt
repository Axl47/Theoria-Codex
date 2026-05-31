package com.theoriacodex.sources.nhentai

import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.http.SourceHttpResponse
import com.theoriacodex.sources.testing.FakeHttpClient
import java.util.ArrayDeque
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NhentaiSourceAdapterTest {
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

        assertEquals("https://nhentai.net/api/galleries/search", httpClient.lastGet?.url)
        assertEquals("1", httpClient.lastGet?.query?.get("page"))
        assertEquals("big breasts -loli", httpClient.lastGet?.query?.get("query"))
        assertEquals("popular-today", httpClient.lastGet?.query?.get("sort"))
        assertEquals("Mozilla/5.0", httpClient.lastGet?.headers?.get("User-Agent"))
        assertEquals("https://nhentai.net/", httpClient.lastGet?.headers?.get("Referer"))
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

        val page = adapter.search(sampleQuery(), pageToken = null)

        assertEquals("2", page.nextPageToken)
        val post = page.items.firstOrNull()
        assertNotNull(post)
        assertEquals(SourceKey.NHENTAI, post?.id?.source)
        assertEquals("https://t.nhentai.net/galleries/9876/thumb.webp", post?.preview?.url)
        assertEquals(2, post?.media?.size)
        assertEquals("https://i.nhentai.net/galleries/9876/1.webp", post?.media?.get(0)?.url)
        assertEquals("https://i.nhentai.net/galleries/9876/2.gif", post?.media?.get(1)?.url)
        assertEquals("https://nhentai.net/g/123/", post?.pageUrl)
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

        val failure = runCatching { adapter.search(sampleQuery(), pageToken = null) }.exceptionOrNull()

        require(failure is SourceAdapterException)
        assertEquals(SourceFailureReason.NETWORK, failure.reason)
        assertTrue(failure.message?.contains("non-JSON", ignoreCase = true) == true)
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

        assertEquals("https://nhentai.net/api/gallery/634609", httpClient.lastGet?.url)
        assertTrue(httpClient.lastGet?.query?.isEmpty() == true)
        assertEquals(1, page.items.size)
        assertEquals("634609", page.items.first().id.sourcePostId)
    }

    @Test
    fun `search falls back to mirrored web page when api is cloudflare blocked`() = runTest {
        val httpClient = QueueHttpClient(
            SourceHttpResponse(
                statusCode = 403,
                body = "<html>Attention Required! Cloudflare</html>",
                headers = mapOf("cf-mitigated" to listOf("challenge")),
            ),
            SourceHttpResponse(
                statusCode = 200,
                body = """
                    Title: test - Search - nhentai

                    Markdown Content:
                    ## 2,300 results

                    [![Image 1: First Mirrored Gallery](https://t4.nhentai.net/galleries/3962373/thumb.webp) First Mirrored Gallery](https://nhentai.net/g/653211/)
                    [1](https://nhentai.net/search?q=test&sort=date&page=1)[2](https://nhentai.net/search?q=test&sort=date&page=2)
                """.trimIndent(),
            ),
        )
        val adapter = NhentaiSourceAdapter(httpClient = httpClient)

        val page = adapter.search(sampleQuery(), pageToken = null)

        assertEquals(2, httpClient.requests.size)
        assertEquals("https://nhentai.net/api/galleries/search", httpClient.requests[0].url)
        assertEquals(
            "https://r.jina.ai/http://http://nhentai.net/search/?q=big%20breasts&page=1&sort=date",
            httpClient.requests[1].url,
        )
        assertEquals("2", page.nextPageToken)
        val post = page.items.single()
        assertEquals("653211", post.id.sourcePostId)
        assertEquals("First Mirrored Gallery", post.title)
        assertEquals("https://t.nhentai.net/galleries/3962373/thumb.webp", post.preview.url)
        assertNull(post.full)
        assertTrue(post.media.isEmpty())
    }

    @Test
    fun `resolve post falls back to mirrored reader page when api is cloudflare blocked`() = runTest {
        val httpClient = QueueHttpClient(
            SourceHttpResponse(
                statusCode = 403,
                body = "<html>Attention Required! Cloudflare</html>",
                headers = mapOf("cf-mitigated" to listOf("challenge")),
            ),
            SourceHttpResponse(
                statusCode = 200,
                body = """
                    Title: Mirrored Gallery - Page 1

                    Markdown Content:
                    [](http://nhentai.net/g/634609/)

                    1 of 3[](http://nhentai.net/g/634609/2/)[](http://nhentai.net/g/634609/3/)

                    [![Image 2: Page 1](https://i2.nhentai.net/galleries/3821534/1.webp)](http://nhentai.net/g/634609/2/)
                """.trimIndent(),
            ),
        )
        val adapter = NhentaiSourceAdapter(httpClient = httpClient)

        val post = adapter.resolvePost(PostId(source = SourceKey.NHENTAI, sourcePostId = "634609"))

        assertNotNull(post)
        assertEquals(2, httpClient.requests.size)
        assertEquals("https://nhentai.net/api/gallery/634609", httpClient.requests[0].url)
        assertEquals("https://r.jina.ai/http://http://nhentai.net/g/634609/1/", httpClient.requests[1].url)
        assertEquals("Mirrored Gallery", post?.title)
        assertEquals(3, post?.media?.size)
        assertEquals("https://i.nhentai.net/galleries/3821534/1.webp", post?.media?.get(0)?.url)
        assertEquals(
            listOf(
                "https://i.nhentai.net/galleries/3821534/1.webp",
                "https://i.nhentai.net/galleries/3821534/1.jpg",
                "https://i.nhentai.net/galleries/3821534/1.png",
                "https://i.nhentai.net/galleries/3821534/1.gif",
            ),
            post?.media?.get(0)?.progressiveUrls,
        )
        assertEquals("https://i.nhentai.net/galleries/3821534/3.webp", post?.media?.get(2)?.url)
    }

    private fun sampleQuery(): Query {
        return Query(
            mode = QueryMode.Source(SourceKey.NHENTAI),
            includeTags = listOf("big breasts"),
            excludeTags = emptyList(),
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = null,
        )
    }

    private class QueueHttpClient(
        vararg responses: SourceHttpResponse,
    ) : com.theoriacodex.sources.http.SourceHttpClient {
        val requests = mutableListOf<com.theoriacodex.sources.testing.RecordedRequest>()
        private val queue = ArrayDeque(responses.toList())

        override suspend fun get(
            url: String,
            query: Map<String, String>,
            headers: Map<String, String>,
        ): SourceHttpResponse {
            requests += com.theoriacodex.sources.testing.RecordedRequest(url, query, headers)
            return queue.removeFirst()
        }

        override suspend fun postForm(
            url: String,
            form: Map<String, String>,
            headers: Map<String, String>,
        ): SourceHttpResponse {
            error("POST is not used by NHentai tests")
        }
    }
}
