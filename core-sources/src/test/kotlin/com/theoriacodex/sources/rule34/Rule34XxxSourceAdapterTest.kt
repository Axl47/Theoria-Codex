package com.theoriacodex.sources.rule34

import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.credentials.Rule34XxxCredentials
import com.theoriacodex.sources.http.SourceHttpResponse
import com.theoriacodex.sources.testing.FakeCredentialsProvider
import com.theoriacodex.sources.testing.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Rule34XxxSourceAdapterTest {
    @Test
    fun `search attaches credentials and maps canonical post url`() = runTest {
        val credentials = FakeCredentialsProvider().apply {
            rule34XxxCredentials = Rule34XxxCredentials(userId = "42", apiKey = "secret")
        }
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = """
                    [
                      {
                        "id":"123456",
                        "preview_url":"https://img.rule34.xxx/preview.jpg",
                        "file_url":"https://img.rule34.xxx/full.jpg",
                        "tags":"genshin_impact lumine"
                      }
                    ]
                """.trimIndent(),
            )
        }
        val adapter = Rule34XxxSourceAdapter(httpClient = httpClient, credentialsProvider = credentials)

        val page = adapter.search(sampleQuery(), pageToken = null)

        assertEquals("42", httpClient.lastGet?.query?.get("user_id"))
        assertEquals("secret", httpClient.lastGet?.query?.get("api_key"))
        assertEquals(
            "https://rule34.xxx/index.php?page=post&s=view&id=123456",
            page.items.first().pageUrl,
        )
    }

    @Test
    fun `autocomplete parses json suggestions`() = runTest {
        val adapter = Rule34XxxSourceAdapter(
            httpClient = FakeHttpClient().apply {
                nextGetResponse = SourceHttpResponse(
                    statusCode = 200,
                    body = """
                        [
                          {"label":"genshin_impact (237880)","value":"genshin_impact"},
                          {"label":"genshin (59)","value":"genshin"}
                        ]
                    """.trimIndent(),
                )
            },
            credentialsProvider = FakeCredentialsProvider(),
        )

        val suggestions = adapter.autocompleteTags("gensh", limit = 10)

        assertEquals("genshin_impact", suggestions.first().text)
        assertEquals(237880, suggestions.first().count)
        assertEquals("genshin", suggestions[1].text)
    }

    @Test
    fun `search skips non-object records and tolerates malformed optional values`() = runTest {
        val credentials = FakeCredentialsProvider().apply {
            rule34XxxCredentials = Rule34XxxCredentials(userId = "42", apiKey = "secret")
        }
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = """
                    [
                      null,
                      "not-a-post",
                      {
                        "id":123456,
                        "preview_url":{},
                        "file_url":"https://img.rule34.xxx/full.jpg",
                        "tags":[],
                        "width":"wide",
                        "created_at":"not-a-timestamp"
                      }
                    ]
                """.trimIndent(),
            )
        }
        val adapter = Rule34XxxSourceAdapter(httpClient = httpClient, credentialsProvider = credentials)

        val post = adapter.search(sampleQuery(), pageToken = null).items.single()

        assertEquals("123456", post.id.sourcePostId)
        assertEquals("https://img.rule34.xxx/full.jpg", post.preview.url)
        assertNull(post.width)
        assertNull(post.createdAtEpochMs)
        assertEquals(emptyList<String>(), post.canonicalTags)
    }

    @Test
    fun `search without credentials returns auth required`() = runTest {
        val adapter = Rule34XxxSourceAdapter(
            httpClient = FakeHttpClient(),
            credentialsProvider = FakeCredentialsProvider(),
        )

        val failure = runCatching { adapter.search(sampleQuery(), pageToken = null) }.exceptionOrNull()

        require(failure is SourceAdapterException)
        assertEquals(SourceFailureReason.AUTH_REQUIRED, failure.reason)
        assertTrue(failure.message?.contains("credentials", ignoreCase = true) == true)
    }

    private fun sampleQuery(): Query {
        return Query(
            mode = QueryMode.Source(SourceKey.RULE34XXX),
            includeTags = listOf("genshin_impact"),
            excludeTags = emptyList(),
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = null,
        )
    }
}
