package com.theoriacodex.sources.nhentai

import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.http.SourceHttpResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class NhentaiRelatedPostsSourceAdapterTest : NhentaiSourceAdapterTestFixture() {
    @Test
    fun `more like this preserves provider order and excludes seed duplicates and invalid records`() = runTest {
        val httpClient = QueueHttpClient(
            SourceHttpResponse(
                statusCode = 200,
                body = """
                    {"result":[
                      ${gallery(10)},
                      null,
                      {"id":99},
                      ${gallery(30)},
                      ${gallery(30)},
                      ${gallery(20)},
                      ${gallery(40)}
                    ]}
                """.trimIndent(),
            ),
        )
        val adapter = NhentaiSourceAdapter(httpClient, minRequestIntervalMs = 0L)

        val posts = adapter.relatedPosts(PostId(SourceKey.NHENTAI, "10"), limit = 2)

        assertEquals(listOf("30", "20"), posts.map { it.id.sourcePostId })
        assertEquals("https://nhentai.net/api/v2/galleries/10/related", httpClient.requests.single().url)
        assertTrue(httpClient.requests.single().query.isEmpty())
        assertTrue(httpClient.postRequests.isEmpty())
        assertEquals("https://t.nhentai.net/galleries/1030/thumb.webp", posts.first().preview.url)
        assertEquals("Gallery 30", posts.first().title)
        assertEquals(12, posts.first().mediaCount)
        // List responses remain unresolved so opening a suggestion loads the full gallery.
        assertTrue(posts.first().media.isEmpty())
        assertNull(posts.first().full)
    }

    @Test
    fun `more like this is bounded to six posts`() = runTest {
        val httpClient = QueueHttpClient(
            SourceHttpResponse(200, """{"result":[${(20..27).joinToString(",", transform = ::gallery)}]}"""),
        )
        val adapter = NhentaiSourceAdapter(httpClient, minRequestIntervalMs = 0L)

        val posts = adapter.relatedPosts(PostId(SourceKey.NHENTAI, "10"), limit = Int.MAX_VALUE)

        assertEquals((20..25).map(Int::toString), posts.map { it.id.sourcePostId })
        assertEquals(1, httpClient.requests.size)
    }

    @Test
    fun `empty recommendations produce an empty shelf`() = runTest {
        val adapter = NhentaiSourceAdapter(
            QueueHttpClient(SourceHttpResponse(200, """{"result":[]}""")),
            minRequestIntervalMs = 0L,
        )

        assertTrue(adapter.relatedPosts(PostId(SourceKey.NHENTAI, "10"), limit = 6).isEmpty())
    }

    @Test
    fun `invalid seeds and nonpositive limits do not request recommendations`() = runTest {
        val httpClient = QueueHttpClient()
        val adapter = NhentaiSourceAdapter(httpClient, minRequestIntervalMs = 0L)

        listOf(
            PostId(SourceKey.PIXIV, "10") to 6,
            PostId(SourceKey.NHENTAI, "") to 6,
            PostId(SourceKey.NHENTAI, "10/related") to 6,
            PostId(SourceKey.NHENTAI, "10") to 0,
            PostId(SourceKey.NHENTAI, "10") to -1,
        ).forEach { (seed, limit) ->
            assertTrue(adapter.relatedPosts(seed, limit).isEmpty())
        }

        assertTrue(httpClient.requests.isEmpty())
    }

    @Test
    fun `provider failures remain errors so the shelf can retry`() = runTest {
        listOf(
            SourceHttpResponse(429, "{}") to SourceFailureReason.RATE_LIMITED,
            SourceHttpResponse(403, "<html>Cloudflare</html>") to SourceFailureReason.UNKNOWN,
            SourceHttpResponse(200, "not json") to SourceFailureReason.PARSE,
            SourceHttpResponse(200, "{}") to SourceFailureReason.PARSE,
        ).forEach { (response, reason) ->
            val httpClient = QueueHttpClient(response)
            val adapter = NhentaiSourceAdapter(httpClient, minRequestIntervalMs = 0L)

            val failure = runCatching {
                adapter.relatedPosts(PostId(SourceKey.NHENTAI, "10"), limit = 6)
            }.exceptionOrNull()

            require(failure is SourceAdapterException)
            assertEquals(reason, failure.reason)
            assertEquals(1, httpClient.requests.size)
        }
    }

    private fun gallery(id: Int): String = """
        {
          "id":$id,
          "media_id":"${id + 1000}",
          "english_title":"Gallery $id",
          "thumbnail":"galleries/${id + 1000}/thumb.webp",
          "thumbnail_width":200,
          "thumbnail_height":300,
          "num_pages":12
        }
    """.trimIndent()
}
