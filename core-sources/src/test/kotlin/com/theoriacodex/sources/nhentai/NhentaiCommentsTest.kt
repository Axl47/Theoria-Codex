package com.theoriacodex.sources.nhentai

import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.testing.ScriptedHttpClient
import com.theoriacodex.sources.testing.commentTestPost
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NhentaiCommentsTest {
    private val http = ScriptedHttpClient()
    private val adapter = NhentaiSourceAdapter(http, minRequestIntervalMs = 0L)
    private val post = commentTestPost(SourceKey.NHENTAI, "123")

    @Test
    fun `a page maps authors, absolute avatars and epoch milliseconds and points at the next page`() = runTest {
        http.enqueueGet(
            url = "https://nhentai.net/api/v2/galleries/123/comments",
            query = mapOf("page" to "1", "per_page" to "50"),
            body = """
                {"result":[
                  {"id":9,"gallery_id":123,"post_date":1700000000,"body":"text one",
                   "poster":{"id":1,"username":"alice","slug":"alice","avatar_url":"avatars/1.png?_=abc"}},
                  {"id":10,"gallery_id":123,"post_date":1700000100,"body":"text two","poster":{"id":2,"username":"bob"}}
                ],"num_pages":3,"per_page":50,"total":120}
            """.trimIndent(),
        )

        val page = adapter.comments(post, pageToken = null)

        assertEquals(listOf("9", "10"), page.comments.map { it.id })
        assertEquals("alice", page.comments[0].authorName)
        assertEquals("https://i.nhentai.net/avatars/1.png?_=abc", page.comments[0].authorAvatarUrl)
        assertNull(page.comments[1].authorAvatarUrl)
        assertEquals(
            CreatorProfile(SourceKey.NHENTAI, "alice", "1", "https://nhentai.net/users/1/alice", uploadsQuery = null),
            page.comments[0].author,
        )
        // Without a slug the profile link is the id alone.
        assertEquals("https://nhentai.net/users/2", page.comments[1].author?.profileUrl)
        assertNull(adapter.nativeCommentAnchor("9"))
        assertEquals(1_700_000_000_000L, page.comments[0].createdAtEpochMs)
        assertEquals("text one", page.comments[0].body)
        assertEquals(120, page.total)
        assertEquals("2", page.nextPageToken)
        http.assertExhausted()
    }

    @Test
    fun `the last page has no next token`() = runTest {
        http.enqueueGet(
            url = "https://nhentai.net/api/v2/galleries/123/comments",
            query = mapOf("page" to "3", "per_page" to "50"),
            body = """{"result":[],"num_pages":3,"per_page":50,"total":120}""",
        )

        val page = adapter.comments(post, pageToken = "3")

        assertNull(page.nextPageToken)
        http.assertExhausted()
    }

    @Test
    fun `a failing request is a source failure`() = runTest {
        http.enqueueGet(url = "https://nhentai.net/api/v2/galleries/123/comments", query = mapOf("page" to "1", "per_page" to "50"), statusCode = 503)

        val failure = runCatching { adapter.comments(post, pageToken = null) }.exceptionOrNull()

        assertEquals(SourceFailureReason.NETWORK, (failure as SourceAdapterException).reason)
    }
}
