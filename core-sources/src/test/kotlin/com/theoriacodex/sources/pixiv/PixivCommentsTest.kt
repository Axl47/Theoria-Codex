package com.theoriacodex.sources.pixiv

import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.model.PostComment
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.credentials.PixivAuthTokens
import com.theoriacodex.sources.http.SourceHttpResponse
import com.theoriacodex.sources.testing.FakeCredentialsProvider
import com.theoriacodex.sources.testing.RoutedHttpClient
import com.theoriacodex.sources.testing.commentTestPost
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PixivCommentsTest {
    private val post = commentTestPost(SourceKey.PIXIV, "55")
    private val comments = "https://app-api.pixiv.net/v3/illust/comments"
    private val detail = "https://app-api.pixiv.net/v1/illust/detail"
    private val nextUrl = "https://app-api.pixiv.net/v3/illust/comments?illust_id=55&offset=30"

    private fun adapter(client: RoutedHttpClient) = PixivSourceAdapter(
        httpClient = client,
        credentialsProvider = FakeCredentialsProvider().apply { pixivTokens = PixivAuthTokens("access", "refresh", Long.MAX_VALUE) },
        minRequestIntervalMs = 0L,
    )

    private val firstPage = """
        {"comments":[
          {"id":1,"comment":"text","date":"2026-01-02T03:04:05+09:00","has_replies":true,
           "user":{"id":7,"name":"alice","account":"a","profile_image_urls":{"medium":"https://i.pximg.net/u/7.png"}},"stamp":null},
          {"id":2,"comment":"","date":"2026-01-02T03:05:00+09:00","has_replies":false,
           "user":{"id":8,"name":"bob","account":"b","profile_image_urls":{}},
           "stamp":{"stamp_id":101,"stamp_url":"https://s.pximg.net/stamp/101.png"}}
        ],"next_url":"$nextUrl","comment_access_control":0}
    """.trimIndent()

    @Test
    fun `the first page maps comments and stamps and takes its total from the illustration`() = runTest {
        val client = RoutedHttpClient(
            mapOf(
                comments to SourceHttpResponse(200, firstPage),
                detail to SourceHttpResponse(200, """{"illust":{"id":55,"total_comments":64}}"""),
            ),
        )

        val page = adapter(client).comments(post, pageToken = null)

        assertEquals(64, page.total)
        assertEquals(nextUrl, page.nextPageToken)
        val (first, stamped) = page.comments
        assertEquals("alice", first.authorName)
        assertEquals("https://i.pximg.net/u/7.png", first.authorAvatarUrl)
        assertEquals(1_767_290_645_000L, first.createdAtEpochMs)
        assertTrue(first.hasReplies)
        assertNull(first.stampUrl)
        assertEquals("", stamped.body)
        assertEquals("https://s.pximg.net/stamp/101.png", stamped.stampUrl)
        assertNull(stamped.authorAvatarUrl)
        assertFalse(stamped.hasReplies)
        assertEquals("55", client.requests.first { it.first == comments }.second["illust_id"])
    }

    @Test
    fun `a failed total lookup leaves the total unknown instead of failing the page`() = runTest {
        val client = RoutedHttpClient(
            mapOf(comments to SourceHttpResponse(200, firstPage), detail to SourceHttpResponse(500, "")),
        )

        val page = adapter(client).comments(post, pageToken = null)

        assertNull(page.total)
        assertEquals(2, page.comments.size)
    }

    @Test
    fun `a page token is requested as given`() = runTest {
        val client = RoutedHttpClient(mapOf(nextUrl to SourceHttpResponse(200, """{"comments":[],"next_url":null}""")))

        val page = adapter(client).comments(post, pageToken = nextUrl)

        assertEquals(listOf(nextUrl to emptyMap<String, String>()), client.requests)
        assertNull(page.nextPageToken)
        assertNull(page.total)
    }

    @Test
    fun `replies are requested for the parent comment`() = runTest {
        val replies = "https://app-api.pixiv.net/v2/illust/comment/replies"
        val client = RoutedHttpClient(
            mapOf(
                replies to SourceHttpResponse(
                    200,
                    """{"comments":[{"id":3,"comment":"re","date":"2026-01-02T04:00:00+09:00","user":{"id":9,"name":"cy"}}],"next_url":null}""",
                ),
            ),
        )
        val parent = PostComment("1", "alice", null, "text", null, hasReplies = true)

        val page = adapter(client).comments(post, pageToken = null, parent = parent)

        assertEquals(listOf("3"), page.comments.map { it.id })
        assertEquals(listOf(replies to mapOf("comment_id" to "1")), client.requests)
    }

    @Test
    fun `a next link outside Pixiv's API is never followed with the token`() = runTest {
        val client = RoutedHttpClient(emptyMap())

        val failure = runCatching { adapter(client).comments(post, pageToken = "https://evil.example/x") }.exceptionOrNull()

        assertEquals(SourceFailureReason.PARSE, (failure as SourceAdapterException).reason)
        assertTrue(client.requests.isEmpty())
    }
}
