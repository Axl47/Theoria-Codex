package com.theoriacodex.sources.iwara

import com.theoriacodex.domain.model.AccountDetails
import com.theoriacodex.domain.model.AccountFeed
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.http.SourceHttpResponse
import com.theoriacodex.sources.testing.RoutedHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IwaraAccountsTest {
    private val api = "https://api.iwara.tv"
    private val account = CreatorProfile(
        SourceKey.IWARA, "Alice", profileId = "u-1", profileUrl = "https://www.iwara.tv/profile/alice/videos", uploadsQuery = "u-1",
    )

    @Test
    fun `a creator with a profile id is an account with uploads only`() {
        val adapter = IwaraSourceAdapter(RoutedHttpClient(emptyMap()))

        assertEquals(listOf(AccountFeed.UPLOADS), adapter.accountFeeds(account))
        assertEquals(emptyList<AccountFeed>(), adapter.accountFeeds(account.copy(profileId = null)))
        assertEquals(emptyList<AccountFeed>(), adapter.accountFeeds(account.copy(source = SourceKey.PIXIV)))
    }

    @Test
    fun `details join the profile with the follower count`() = runTest {
        val client = RoutedHttpClient(
            mapOf(
                "$api/profile/alice" to SourceHttpResponse(
                    200,
                    """{"body":" hello ","user":{"id":"u-1","username":"alice","createdAt":"2020-03-04T05:06:07.000Z","avatar":{"id":"f-9","name":"a.PNG"}}}""",
                ),
                "$api/user/u-1/followers" to SourceHttpResponse(200, """{"count":6924,"limit":1,"page":0,"results":[]}"""),
            ),
        )

        assertEquals(
            AccountDetails(
                avatarUrl = "https://i.iwara.tv/image/thumbnail/f-9/f-9.png",
                bio = "hello",
                joinedAtEpochMs = 1_583_298_367_000L,
                followerCount = 6924,
            ),
            IwaraSourceAdapter(client).accountDetails(account),
        )
        assertEquals(mapOf("limit" to "1"), client.requests.last().second)
    }

    @Test
    fun `an avatar-less profile with no bio keeps only what it has`() = runTest {
        val client = RoutedHttpClient(
            mapOf(
                "$api/profile/alice" to SourceHttpResponse(200, """{"body":null,"user":{"id":"u-1","avatar":null}}"""),
                "$api/user/u-1/followers" to SourceHttpResponse(200, """{"count":0,"results":[]}"""),
            ),
        )

        assertEquals(AccountDetails(followerCount = 0), IwaraSourceAdapter(client).accountDetails(account))
    }

    @Test
    fun `an unknown user has no details and a profile link without a name cannot be asked`() = runTest {
        val client = RoutedHttpClient(mapOf("$api/profile/alice" to SourceHttpResponse(404, "{}")))
        val adapter = IwaraSourceAdapter(client)

        assertNull(adapter.accountDetails(account))
        assertNull(adapter.accountDetails(account.copy(profileUrl = null)))
        assertEquals(1, client.requests.size)
    }

    @Test
    fun `uploads are the creator videos search`() = runTest {
        val client = RoutedHttpClient(mapOf("$api/videos" to SourceHttpResponse(200, """{"page":0,"count":0,"limit":32,"results":[]}""")))

        IwaraSourceAdapter(client).accountPosts(account, AccountFeed.UPLOADS, pageToken = null)

        assertEquals("u-1", client.requests.single().second["user"])
    }
}
