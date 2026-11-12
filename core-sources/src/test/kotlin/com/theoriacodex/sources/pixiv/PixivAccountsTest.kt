package com.theoriacodex.sources.pixiv

import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.model.AccountDetails
import com.theoriacodex.domain.model.AccountFeed
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.credentials.PixivAuthTokens
import com.theoriacodex.sources.http.SourceHttpResponse
import com.theoriacodex.sources.testing.FakeCredentialsProvider
import com.theoriacodex.sources.testing.RoutedHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Response shapes follow Pixiv's app-api documentation; they have not been checked against a live account. */
class PixivAccountsTest {
    private val api = "https://app-api.pixiv.net"
    private val account = pixivUserProfile("7", "alice")!!

    private fun adapter(client: RoutedHttpClient) = PixivSourceAdapter(
        httpClient = client,
        credentialsProvider = FakeCredentialsProvider().apply { pixivTokens = PixivAuthTokens("access", "refresh", Long.MAX_VALUE) },
        minRequestIntervalMs = 0L,
    )

    private fun illust(id: Int) =
        """{"id":$id,"title":"t","type":"illust","image_urls":{"medium":"https://i.pximg.net/$id.jpg"},"user":{"id":7,"name":"alice"},"width":1,"height":1,"tags":[]}"""

    @Test
    fun `details read the avatar, bio and totals but not who the user follows`() {
        val root = com.google.gson.JsonParser.parseString(
            """{"user":{"id":7,"name":"alice","profile_image_urls":{"medium":"https://i.pximg.net/u/7.png"},"comment":" about me "},
                "profile":{"total_illusts":10,"total_manga":4,"total_follow_users":99,"total_illust_bookmarks_public":21}}""",
        ).asJsonObject

        assertEquals(
            AccountDetails(avatarUrl = "https://i.pximg.net/u/7.png", bio = "about me", uploadCount = 14, favoriteCount = 21),
            parsePixivAccountDetails(root),
        )
        assertNull(parsePixivAccountDetails(com.google.gson.JsonObject()))
    }

    @Test
    fun `any creator with a user id is an account`() {
        val adapter = adapter(RoutedHttpClient(emptyMap()))

        assertEquals(listOf(AccountFeed.UPLOADS, AccountFeed.FAVORITES), adapter.accountFeeds(account))
        assertEquals(emptyList<AccountFeed>(), adapter.accountFeeds(CreatorProfile(SourceKey.PIXIV, "alice")))
        assertEquals(emptyList<AccountFeed>(), adapter.accountFeeds(account.copy(source = SourceKey.IWARA)))
    }

    @Test
    fun `details ask for the user by id`() = runTest {
        val client = RoutedHttpClient(
            mapOf("$api/v1/user/detail" to SourceHttpResponse(200, """{"user":{"id":7,"comment":""},"profile":{"total_illusts":1}}""")),
        )

        assertEquals(AccountDetails(uploadCount = 1), adapter(client).accountDetails(account))
        assertEquals(mapOf("user_id" to "7", "filter" to "for_android"), client.requests.single().second)
    }

    @Test
    fun `favorites are public bookmarks and continue through next_url`() = runTest {
        val next = "$api/v1/user/bookmarks/illust?user_id=7&restrict=public&max_bookmark_id=900"
        val client = RoutedHttpClient(
            mapOf(
                "$api/v1/user/bookmarks/illust" to SourceHttpResponse(200, """{"illusts":[${illust(1)}],"next_url":"$next"}"""),
                next to SourceHttpResponse(200, """{"illusts":[${illust(2)}],"next_url":null}"""),
            ),
        )
        val adapter = adapter(client)

        val first = adapter.accountPosts(account, AccountFeed.FAVORITES, pageToken = null)
        val second = adapter.accountPosts(account, AccountFeed.FAVORITES, pageToken = first.nextPageToken)

        assertEquals(mapOf("user_id" to "7", "restrict" to "public", "filter" to "for_android"), client.requests.first().second)
        assertEquals(next, first.nextPageToken)
        assertEquals(listOf("2"), second.items.map { it.id.sourcePostId })
        assertNull(second.nextPageToken)
    }

    @Test
    fun `a next page link away from the Pixiv API is refused before the token is sent`() = runTest {
        val client = RoutedHttpClient(emptyMap())

        val failure = runCatching { adapter(client).accountPosts(account, AccountFeed.FAVORITES, "https://evil.example/x") }.exceptionOrNull()

        assertEquals(SourceFailureReason.PARSE, (failure as SourceAdapterException).reason)
        assertEquals(emptyList<Any>(), client.requests)
    }

    @Test
    fun `uploads are the creator uploads search`() = runTest {
        val client = RoutedHttpClient(mapOf("$api/v1/user/illusts" to SourceHttpResponse(200, """{"illusts":[${illust(3)}]}""")))

        val page = adapter(client).accountPosts(account, AccountFeed.UPLOADS, pageToken = null)

        assertEquals(listOf("3"), page.items.map { it.id.sourcePostId })
        assertEquals("7", client.requests.single().second["user_id"])
    }
}
