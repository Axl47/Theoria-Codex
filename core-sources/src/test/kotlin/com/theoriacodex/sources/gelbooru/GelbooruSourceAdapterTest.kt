package com.theoriacodex.sources.gelbooru

import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.credentials.GelbooruCredentials
import com.theoriacodex.sources.http.SourceHttpResponse
import com.theoriacodex.sources.testing.FakeCredentialsProvider
import com.theoriacodex.sources.testing.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GelbooruSourceAdapterTest {
    @Test
    fun `search attaches credentials when configured`() = runTest {
        val credentials = FakeCredentialsProvider().apply {
            gelbooruCredentials = GelbooruCredentials(userId = "user1", apiKey = "key1")
        }
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = """{"post":[{"id":"1","preview_url":"https://gelbooru.com/p.jpg","file_url":"https://gelbooru.com/f.jpg","tags":"a b"}]}""",
            )
        }
        val adapter = GelbooruSourceAdapter(
            httpClient = httpClient,
            credentialsProvider = credentials,
        )

        adapter.search(sampleQuery(), pageToken = null)

        assertEquals("user1", httpClient.lastGet?.query?.get("user_id"))
        assertEquals("key1", httpClient.lastGet?.query?.get("api_key"))
    }

    @Test
    fun `search maps auth blocked response to auth required`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = """{"success":false,"message":"API key required"}""",
            )
        }
        val adapter = GelbooruSourceAdapter(
            httpClient = httpClient,
            credentialsProvider = FakeCredentialsProvider(),
        )

        val failure = runCatching { adapter.search(sampleQuery(), pageToken = null) }.exceptionOrNull()

        require(failure is SourceAdapterException)
        assertEquals(SourceFailureReason.AUTH_REQUIRED, failure.reason)
        assertTrue(failure.message?.contains("credentials", ignoreCase = true) == true)
    }

    @Test
    fun `search maps canonical index php post url`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = """{"post":[{"id":"12345678","preview_url":"https://gelbooru.com/p.jpg","file_url":"https://gelbooru.com/f.jpg","tags":"a b"}]}""",
            )
        }
        val adapter = GelbooruSourceAdapter(
            httpClient = httpClient,
            credentialsProvider = FakeCredentialsProvider(),
        )

        val page = adapter.search(sampleQuery(), pageToken = null)

        assertEquals(
            "https://gelbooru.com/index.php?page=post&s=view&id=12345678",
            page.items.firstOrNull()?.pageUrl,
        )
    }

    private fun sampleQuery(): Query {
        return Query(
            mode = QueryMode.Source(SourceKey.GELBOORU),
            includeTags = listOf("landscape"),
            excludeTags = emptyList(),
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = null,
        )
    }
}
