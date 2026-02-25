package com.theoriacodex.sources.pixiv

import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.credentials.PixivAuthTokens
import com.theoriacodex.sources.http.SourceHttpResponse
import com.theoriacodex.sources.testing.FakeCredentialsProvider
import com.theoriacodex.sources.testing.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
                          "user": {"name": "artist"}
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
        assertTrue(httpClient.lastGet?.headers?.containsKey("Authorization") == true)
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
