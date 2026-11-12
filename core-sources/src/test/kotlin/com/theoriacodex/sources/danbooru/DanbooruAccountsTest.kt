package com.theoriacodex.sources.danbooru

import com.theoriacodex.domain.model.AccountDetails
import com.theoriacodex.domain.model.AccountFeed
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.testing.ScriptedHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DanbooruAccountsTest {
    private val http = ScriptedHttpClient()
    private val adapter = DanbooruSourceAdapter(DanbooruSite.DANBOORU, http)
    private val base = "https://danbooru.donmai.us"

    private val account = CreatorProfile(
        SourceKey.DANBOORU, "alice", profileId = "11", profileUrl = "$base/users/11", uploadsQuery = "user:alice",
    )
    private val artistTag = CreatorProfile(
        SourceKey.DANBOORU, "some artist", profileId = "some_artist", profileUrl = "$base/posts?tags=some_artist", uploadsQuery = "some_artist",
    )

    /** A full page of 40 records with descending ids from [firstId]. */
    private fun fullPage(firstId: Int) = (0 until 40).joinToString(",", "[", "]") { offset ->
        """{"id":${firstId - offset},"file_url":"https://cdn.example/a.jpg","tag_string":"a"}"""
    }

    private fun postsQuery(tags: String, page: String) = mapOf("limit" to "40", "page" to page, "tags" to tags)

    @Test
    fun `only user creators are accounts`() {
        assertEquals(listOf(AccountFeed.UPLOADS, AccountFeed.FAVORITES), adapter.accountFeeds(account))
        assertEquals(emptyList<AccountFeed>(), adapter.accountFeeds(artistTag))
        assertEquals(emptyList<AccountFeed>(), adapter.accountFeeds(account.copy(uploadsQuery = "user:")))
        assertEquals(emptyList<AccountFeed>(), adapter.accountFeeds(account.copy(source = SourceKey.AIBOORU)))
    }

    @Test
    fun `details combine the user record with the favorite count`() = runTest {
        http.enqueueGet(
            "$base/users/11.json",
            body = """{"id":11,"created_at":"2007-09-10T16:31:08.995-04:00","name":"alice","level":20,"post_upload_count":123}""",
        )
        http.enqueueGet("$base/counts/posts.json", mapOf("tags" to "ordfav:alice"), body = """{"counts":{"posts":45}}""")

        assertEquals(
            AccountDetails(joinedAtEpochMs = 1_189_456_268_995L, uploadCount = 123, favoriteCount = 45),
            adapter.accountDetails(account),
        )
        http.assertExhausted()
    }

    @Test
    fun `private favorites count as zero`() = runTest {
        http.enqueueGet("$base/users/11.json", body = """{"id":11,"post_upload_count":2}""")
        http.enqueueGet("$base/counts/posts.json", mapOf("tags" to "ordfav:alice"), body = """{"counts":{"posts":0}}""")

        assertEquals(AccountDetails(uploadCount = 2, favoriteCount = 0), adapter.accountDetails(account))
    }

    @Test
    fun `an artist tag has no account details and asks for nothing`() = runTest {
        assertNull(adapter.accountDetails(artistTag))
        http.assertExhausted()
    }

    @Test
    fun `favorites page by number because ordfav is not id ordered`() = runTest {
        http.enqueueGet("$base/posts.json", postsQuery("ordfav:alice", "1"), body = fullPage(500))
        http.enqueueGet("$base/posts.json", postsQuery("ordfav:alice", "2"), body = "[]")

        val first = adapter.accountPosts(account, AccountFeed.FAVORITES, pageToken = null)
        assertEquals("2", first.nextPageToken)
        assertNull(adapter.accountPosts(account, AccountFeed.FAVORITES, pageToken = "2").nextPageToken)
    }

    @Test
    fun `uploads keep the creator uploads search and its id cursor`() = runTest {
        http.enqueueGet("$base/posts.json", postsQuery("user:alice", "1"), body = fullPage(500))

        val page = adapter.accountPosts(account, AccountFeed.UPLOADS, pageToken = null)

        assertEquals("b461", page.nextPageToken)
    }
}
