package com.theoriacodex.sources.pixiv

import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.credentials.PixivAuthTokens
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.http.SourceHttpResponse
import com.theoriacodex.sources.testing.FakeCredentialsProvider
import com.theoriacodex.sources.testing.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PixivSourceAdapterTest {
    @Test
    fun `search requires credentials`() = runTest {
        val adapter = PixivSourceAdapter(
            httpClient = FakeHttpClient(),
            credentialsProvider = FakeCredentialsProvider(),
        )

        val failure = runCatching { adapter.search(sampleQuery(), pageToken = null) }.exceptionOrNull()

        require(failure is SourceAdapterException)
        assertEquals(SourceFailureReason.AUTH_REQUIRED, failure.reason)
    }

    @Test
    fun `search maps response and next offset`() = runTest {
        val credentials = FakeCredentialsProvider().apply {
            pixivTokens = PixivAuthTokens(
                accessToken = "access",
                refreshToken = "refresh",
                expiresAtEpochMs = Long.MAX_VALUE,
            )
        }
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = """
                    {
                      "illusts": [
                        {
                          "id": 12345,
                          "width": 1000,
                          "height": 800,
                          "create_date": "2024-03-01T00:00:00+00:00",
                          "image_urls": {
                            "square_medium": "https://i.pximg.net/sq.jpg",
                            "large": "https://i.pximg.net/lg.jpg"
                          },
                          "tags": [{"name": "landscape"}],
                          "user": {"id": 77, "name": "artist"}
                        }
                      ],
                      "next_url": "https://app-api.pixiv.net/v1/search/illust?offset=30"
                    }
                """.trimIndent(),
            )
        }
        val adapter = PixivSourceAdapter(
            httpClient = httpClient,
            credentialsProvider = credentials,
        )

        val page = adapter.search(sampleQuery(), pageToken = null)

        assertEquals(1, page.items.size)
        assertEquals("30", page.nextPageToken)
        assertEquals(SourceKey.PIXIV, page.items.first().id.source)
        assertEquals("artist", page.items.first().creatorProfile?.displayName)
        assertEquals("https://www.pixiv.net/en/users/77", page.items.first().creatorProfile?.profileUrl)
        assertTrue(httpClient.lastGet?.headers?.containsKey("Authorization") == true)
    }

    @Test
    fun `search maps creator metadata`() = runTest {
        val credentials = FakeCredentialsProvider().apply {
            pixivTokens = PixivAuthTokens(
                accessToken = "access",
                refreshToken = "refresh",
                expiresAtEpochMs = Long.MAX_VALUE,
            )
        }
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = """
                    {
                      "illusts": [
                        {
                          "id": 12345,
                          "image_urls": {
                            "square_medium": "https://i.pximg.net/sq.jpg",
                            "large": "https://i.pximg.net/lg.jpg"
                          },
                          "user": {"id": 201823, "name": "creator_name"},
                          "tags": [{"name": "landscape"}]
                        }
                      ]
                    }
                """.trimIndent(),
            )
        }
        val adapter = PixivSourceAdapter(
            httpClient = httpClient,
            credentialsProvider = credentials,
        )

        val post = adapter.search(sampleQuery(), pageToken = null).items.first()

        assertNotNull(post.creatorProfile)
        assertEquals("creator_name", post.creatorProfile?.displayName)
        assertEquals("201823", post.creatorProfile?.profileId)
        assertEquals("201823", post.creatorProfile?.uploadsQuery)
        assertEquals("https://www.pixiv.net/en/users/201823", post.creatorProfile?.profileUrl)
    }

    @Test
    fun `resolve ugoira sums metadata frame delays`() = runTest {
        val credentials = FakeCredentialsProvider().apply {
            pixivTokens = PixivAuthTokens(
                accessToken = "access",
                refreshToken = "refresh",
                expiresAtEpochMs = Long.MAX_VALUE,
            )
        }
        val httpClient = QueueHttpClient(
            SourceHttpResponse(
                statusCode = 200,
                body = """
                    {
                      "illust": {
                        "id": 12345,
                        "type": "ugoira",
                        "image_urls": {
                          "square_medium": "https://i.pximg.net/sq.jpg",
                          "large": "https://i.pximg.net/lg.jpg"
                        },
                        "meta_single_page": {
                          "original_image_url": "https://i.pximg.net/ugoira.zip"
                        },
                        "tags": [{"name": "animation"}],
                        "user": {"id": 77, "name": "artist"}
                      }
                    }
                """.trimIndent(),
            ),
            SourceHttpResponse(
                statusCode = 200,
                body = """
                    {
                      "ugoira_metadata": {
                        "frames": [
                          {"file": "000000.jpg", "delay": 80},
                          {"file": "000001.jpg", "delay": 120}
                        ]
                      }
                    }
                """.trimIndent(),
            ),
        )
        val adapter = PixivSourceAdapter(
            httpClient = httpClient,
            credentialsProvider = credentials,
        )

        val post = adapter.resolvePost(com.theoriacodex.domain.model.PostId(SourceKey.PIXIV, "12345"))

        assertEquals(200L, post?.durationMs)
        assertEquals("https://app-api.pixiv.net/v1/ugoira/metadata", httpClient.requests.last().url)
    }


    @Test
    fun `creator search uses user illustrations endpoint and preserves pagination`() = runTest {
        val credentials = FakeCredentialsProvider().apply {
            pixivTokens = PixivAuthTokens(
                accessToken = "access",
                refreshToken = "refresh",
                expiresAtEpochMs = Long.MAX_VALUE,
            )
        }
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = """
                    {
                      "illusts": [
                        {
                          "id": 999,
                          "image_urls": {
                            "square_medium": "https://i.pximg.net/sq.jpg",
                            "large": "https://i.pximg.net/lg.jpg"
                          },
                          "user": {"id": 201823, "name": "creator_name"},
                          "tags": [{"name": "portrait"}]
                        }
                      ],
                      "next_url": "https://app-api.pixiv.net/v1/user/illusts?offset=30"
                    }
                """.trimIndent(),
            )
        }
        val adapter = PixivSourceAdapter(
            httpClient = httpClient,
            credentialsProvider = credentials,
        )

        val page = adapter.searchCreatorPosts(
            creator = CreatorProfile(
                source = SourceKey.PIXIV,
                displayName = "creator_name",
                profileId = "201823",
                profileUrl = "https://www.pixiv.net/en/users/201823",
                uploadsQuery = "201823",
            ),
            pageToken = "60",
        )

        assertEquals("https://app-api.pixiv.net/v1/user/illusts", httpClient.lastGet?.url)
        assertEquals("201823", httpClient.lastGet?.query?.get("user_id"))
        assertEquals("60", httpClient.lastGet?.query?.get("offset"))
        assertEquals("30", page.nextPageToken)
        assertEquals("999", page.items.first().id.sourcePostId)
    }

    @Test
    fun `search normalizes booru-style tags for pixiv word query`() = runTest {
        val credentials = FakeCredentialsProvider().apply {
            pixivTokens = PixivAuthTokens(
                accessToken = "access",
                refreshToken = "refresh",
                expiresAtEpochMs = Long.MAX_VALUE,
            )
        }
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = """
                    {
                      "illusts": []
                    }
                """.trimIndent(),
            )
        }
        val adapter = PixivSourceAdapter(
            httpClient = httpClient,
            credentialsProvider = credentials,
        )

        adapter.search(
            Query(
                mode = QueryMode.Source(SourceKey.PIXIV),
                includeTags = listOf("this_is_a_tag_(Game)"),
                excludeTags = listOf("nsfw_(content)"),
                sort = SortMode.NEWEST,
                dateRange = null,
                minScore = null,
            ),
            pageToken = null,
        )

        assertEquals("this is a tag -nsfw", httpClient.lastGet?.query?.get("word"))
    }

    @Test
    fun `search maps single page pixiv progressive urls`() = runTest {
        val credentials = FakeCredentialsProvider().apply {
            pixivTokens = PixivAuthTokens(
                accessToken = "access",
                refreshToken = "refresh",
                expiresAtEpochMs = Long.MAX_VALUE,
            )
        }
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = """
                    {
                      "illusts": [
                        {
                          "id": 12345,
                          "image_urls": {
                            "square_medium": "https://i.pximg.net/square.jpg",
                            "medium": "https://i.pximg.net/medium.jpg",
                            "large": "https://i.pximg.net/large.jpg"
                          },
                          "meta_single_page": {
                            "original_image_url": "https://i.pximg.net/original.jpg"
                          },
                          "tags": [{"name": "landscape"}],
                          "user": {"id": 77, "name": "artist"}
                        }
                      ]
                    }
                """.trimIndent(),
            )
        }
        val adapter = PixivSourceAdapter(
            httpClient = httpClient,
            credentialsProvider = credentials,
        )

        val post = adapter.search(sampleQuery(), pageToken = null).items.first()
        val media = post.media.first()

        assertEquals("https://i.pximg.net/square.jpg", post.preview.url)
        assertEquals("https://i.pximg.net/original.jpg", post.full?.url)
        assertEquals(
            listOf(
                "https://i.pximg.net/medium.jpg",
                "https://i.pximg.net/large.jpg",
            ),
            media.progressiveUrls,
        )
    }

    @Test
    fun `search maps multipage pixiv progressive urls per page`() = runTest {
        val credentials = FakeCredentialsProvider().apply {
            pixivTokens = PixivAuthTokens(
                accessToken = "access",
                refreshToken = "refresh",
                expiresAtEpochMs = Long.MAX_VALUE,
            )
        }
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = """
                    {
                      "illusts": [
                        {
                          "id": 67890,
                          "image_urls": {
                            "square_medium": "https://i.pximg.net/cover_square.jpg",
                            "medium": "https://i.pximg.net/cover_medium.jpg",
                            "large": "https://i.pximg.net/cover_large.jpg"
                          },
                          "meta_pages": [
                            {
                              "image_urls": {
                                "medium": "https://i.pximg.net/page1_medium.jpg",
                                "large": "https://i.pximg.net/page1_large.jpg",
                                "original": "https://i.pximg.net/page1_original.jpg"
                              }
                            },
                            {
                              "image_urls": {
                                "medium": "https://i.pximg.net/page2_medium.jpg",
                                "large": "https://i.pximg.net/page2_large.jpg",
                                "original": "https://i.pximg.net/page2_original.jpg"
                              }
                            }
                          ],
                          "tags": [{"name": "landscape"}],
                          "user": {"id": 77, "name": "artist"}
                        }
                      ]
                    }
                """.trimIndent(),
            )
        }
        val adapter = PixivSourceAdapter(
            httpClient = httpClient,
            credentialsProvider = credentials,
        )

        val post = adapter.search(sampleQuery(), pageToken = null).items.first()

        assertEquals(2, post.media.size)
        assertEquals("https://i.pximg.net/page1_original.jpg", post.media[0].url)
        assertEquals(
            listOf(
                "https://i.pximg.net/page1_medium.jpg",
                "https://i.pximg.net/page1_large.jpg",
            ),
            post.media[0].progressiveUrls,
        )
        assertEquals("https://i.pximg.net/page2_original.jpg", post.media[1].url)
        assertEquals(
            listOf(
                "https://i.pximg.net/page2_medium.jpg",
                "https://i.pximg.net/page2_large.jpg",
            ),
            post.media[1].progressiveUrls,
        )
    }

    private fun sampleQuery(): Query {
        return Query(
            mode = QueryMode.Source(SourceKey.PIXIV),
            includeTags = listOf("landscape"),
            excludeTags = listOf("nsfw"),
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = null,
        )
    }
}

private class QueueHttpClient(
    vararg responses: SourceHttpResponse,
) : SourceHttpClient {
    private val queue = ArrayDeque(responses.toList())
    val requests = mutableListOf<RecordedPixivRequest>()

    override suspend fun get(
        url: String,
        query: Map<String, String>,
        headers: Map<String, String>,
    ): SourceHttpResponse {
        requests += RecordedPixivRequest(url = url, query = query)
        return queue.removeFirst()
    }

    override suspend fun postForm(
        url: String,
        form: Map<String, String>,
        headers: Map<String, String>,
    ): SourceHttpResponse {
        error("unused")
    }
}

private data class RecordedPixivRequest(
    val url: String,
    val query: Map<String, String>,
)
