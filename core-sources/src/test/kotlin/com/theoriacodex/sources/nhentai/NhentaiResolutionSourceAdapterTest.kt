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

internal class NhentaiResolutionSourceAdapterTest : NhentaiSourceAdapterTestFixture() {
    @Test
    fun `resolve post maps v2 gallery details with page paths`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = """
                    {
                      "id": 634609,
                      "media_id": "3821534",
                      "title": {
                        "english": "English Detail",
                        "japanese": null,
                        "pretty": "Pretty Detail"
                      },
                      "cover": {"path": "galleries/3821534/cover.webp.webp", "width": 350, "height": 494},
                      "thumbnail": {"path": "galleries/3821534/thumb.webp", "width": 250, "height": 353},
                      "scanlator": "",
                      "upload_date": 1772669054,
                      "tags": [
                        {"id": 12227, "type": "language", "name": "english", "slug": "english", "url": "/language/english/", "count": 141113},
                        {"id": 129314, "type": "artist", "name": "kyaradain", "slug": "kyaradain", "url": "/artist/kyaradain/", "count": 106}
                      ],
                      "num_pages": 2,
                      "num_favorites": 100,
                      "pages": [
                        {"number": 2, "path": "galleries/3821534/2.jpg", "width": 1280, "height": 1807, "thumbnail": "galleries/3821534/2t.jpg.webp", "thumbnail_width": 200, "thumbnail_height": 282},
                        {"number": 1, "path": "galleries/3821534/1.webp", "width": 1280, "height": 1807, "thumbnail": "galleries/3821534/1t.webp", "thumbnail_width": 200, "thumbnail_height": 282}
                      ]
                    }
                """.trimIndent(),
            )
        }
        val adapter = NhentaiSourceAdapter(httpClient = httpClient)

        val post = adapter.resolvePost(PostId(source = SourceKey.NHENTAI, sourcePostId = "634609"))

        assertEquals("https://nhentai.net/api/v2/galleries/634609", httpClient.lastGet?.url)
        assertEquals("English Detail", post?.title)
        assertEquals("https://t.nhentai.net/galleries/3821534/thumb.webp", post?.preview?.url)
        assertEquals("https://i.nhentai.net/galleries/3821534/1.webp", post?.media?.get(0)?.url)
        assertEquals("https://i.nhentai.net/galleries/3821534/2.jpg", post?.media?.get(1)?.url)
        assertEquals(2, post?.mediaCount)
        assertEquals(listOf("english", "kyaradain"), post?.canonicalTags)
        assertEquals("kyaradain", post?.authorName)
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

        val page = adapter.search(multiTagQuery(), pageToken = null)

        assertEquals(2, httpClient.requests.size)
        assertEquals("https://nhentai.net/api/v2/search", httpClient.requests[0].url)
        assertEquals(
            "https://r.jina.ai/http://nhentai.net/search/?q=big%20breasts%20english&page=1&sort=date",
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
            SourceHttpResponse(
                statusCode = 200,
                body = """
                    <html>
                      <body>
                        <section id="tags">
                          <a href="/tag/big-breasts/" class="tag"><span class="name">big breasts</span></a>
                          <a href="/artist/yomoda-yomo/" class="tag"><span class="name">yomoda yomo</span></a>
                          <a href="/language/english/" class="tag"><span class="name">english</span></a>
                        </section>
                      </body>
                    </html>
                """.trimIndent(),
            ),
        )
        val adapter = NhentaiSourceAdapter(httpClient = httpClient)

        val post = adapter.resolvePost(PostId(source = SourceKey.NHENTAI, sourcePostId = "634609"))

        assertNotNull(post)
        assertEquals(3, httpClient.requests.size)
        assertEquals("https://nhentai.net/api/v2/galleries/634609", httpClient.requests[0].url)
        assertEquals("https://r.jina.ai/http://nhentai.net/g/634609/1/", httpClient.requests[1].url)
        assertEquals("https://nhentai.to/g/634609/", httpClient.requests[2].url)
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
        assertEquals(listOf("big breasts", "yomoda yomo", "english"), post?.canonicalTags)
        assertEquals(
            listOf(
                Triple(SearchFacet.TAG, "tag", "big breasts"),
                Triple(SearchFacet.ARTIST, "artist", "yomoda yomo"),
                Triple(SearchFacet.LANGUAGE, "language", "english"),
            ),
            post?.taxonomy?.map { term -> Triple(term.facet, term.sourceNamespace, term.value) },
        )
        assertEquals("yomoda yomo", post?.authorName)
    }

    @Test
    fun `exact tag lookup cancellation stops search instead of falling through`() = runTest {
        val expected = CancellationException("query replaced")
        val httpClient = CancellingTagLookupHttpClient(expected)
        val adapter = NhentaiSourceAdapter(httpClient = httpClient, minRequestIntervalMs = 0L)

        var thrown: CancellationException? = null
        try {
            adapter.search(sampleQuery(), pageToken = null)
        } catch (error: CancellationException) {
            thrown = error
        }

        assertTrue(thrown === expected)
        assertEquals(0, httpClient.getCalls)
    }

    @Test
    fun `mirror metadata cancellation stops resolution without trying another mirror`() = runTest {
        val expected = CancellationException("viewer closed")
        val httpClient = CancellingMirrorMetadataHttpClient(expected)
        val adapter = NhentaiSourceAdapter(httpClient = httpClient, minRequestIntervalMs = 0L)

        var thrown: CancellationException? = null
        try {
            adapter.resolvePost(PostId(source = SourceKey.NHENTAI, sourcePostId = "634609"))
        } catch (error: CancellationException) {
            thrown = error
        }

        assertTrue(thrown === expected)
        assertEquals(3, httpClient.getCalls)
    }

}
