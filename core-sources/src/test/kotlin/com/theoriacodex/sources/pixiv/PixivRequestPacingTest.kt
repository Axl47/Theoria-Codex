package com.theoriacodex.sources.pixiv

import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.PIXIV_UGOIRA_MIME
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.credentials.PixivAuthTokens
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.http.SourceHttpResponse
import com.theoriacodex.sources.testing.FakeCredentialsProvider
import java.io.IOException
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PixivRequestPacingTest {
    private val ugoira = Post(
        id = PostId(SourceKey.PIXIV, "12345"),
        preview = ImageRef(url = "https://i.pximg.net/p.jpg", localPath = null, mime = null),
        full = ImageRef(url = "https://i.pximg.net/f", localPath = null, mime = PIXIV_UGOIRA_MIME),
        pageUrl = null,
        width = null,
        height = null,
        canonicalTags = emptyList(),
        rawTags = emptyList(),
        authorName = null,
        createdAtEpochMs = null,
    )

    @Test
    fun `background duration lookups do not delay the next browse request (ADR 0046)`() = runTest {
        val credentials = FakeCredentialsProvider().apply {
            pixivTokens = PixivAuthTokens("access", "refresh", Long.MAX_VALUE)
        }
        val httpClient = QueueHttpClient(
            SourceHttpResponse(200, """{"ugoira_metadata":{"frames":[{"delay":80}]}}"""),
            SourceHttpResponse(200, """{"illusts":[]}"""),
        )
        val adapter = PixivSourceAdapter(
            httpClient = httpClient,
            credentialsProvider = credentials,
            clock = { testScheduler.currentTime + 1_000_000L },
            minRequestIntervalMs = 10_000L,
            callContext = EmptyCoroutineContext,
        )

        adapter.resolveDurationMetadata(ugoira)
        adapter.relatedPosts(PostId(SourceKey.PIXIV, "10"), 30)

        assertEquals(0L, testScheduler.currentTime)
        assertEquals(2, httpClient.requests.size)
    }

    @Test
    fun `duration lookups reuse ugoira metadata another consumer already fetched (ADR 0046)`() = runTest {
        val credentials = FakeCredentialsProvider().apply {
            pixivTokens = PixivAuthTokens("access", "refresh", Long.MAX_VALUE)
        }
        val httpClient = QueueHttpClient()
        val cache = PixivUgoiraMetadataCache().apply {
            put("12345", """{"ugoira_metadata":{"frames":[{"delay":80},{"delay":120}]}}""")
        }
        val adapter = PixivSourceAdapter(
            httpClient = httpClient,
            credentialsProvider = credentials,
            ugoiraMetadataCache = cache,
        )

        assertEquals(
            com.theoriacodex.domain.adapter.DurationMetadataSourceResult.Known(200L),
            adapter.resolveDurationMetadata(ugoira),
        )
        assertEquals(0, httpClient.requests.size)
    }

    @Test
    fun `a completed duration lookup is kept for the player to reuse (ADR 0046)`() = runTest {
        val body = """{"ugoira_metadata":{"frames":[{"delay":40}]}}"""
        val cache = PixivUgoiraMetadataCache()
        val adapter = PixivSourceAdapter(
            httpClient = QueueHttpClient(SourceHttpResponse(200, body)),
            credentialsProvider = FakeCredentialsProvider().apply { pixivTokens = PixivAuthTokens("access", "refresh", Long.MAX_VALUE) },
            ugoiraMetadataCache = cache,
        )

        adapter.resolveDurationMetadata(ugoira)

        assertEquals(body, cache.get("12345"))
    }

    @Test
    fun `a malformed duration answer is not kept, so the next lookup asks Pixiv again (ADR 0046)`() = runTest {
        val body = """{"ugoira_metadata":{"frames":[{"delay":40},{"delay":60}]}}"""
        val cache = PixivUgoiraMetadataCache()
        val httpClient = QueueHttpClient(SourceHttpResponse(200, "<html>busy</html>"), SourceHttpResponse(200, body))
        val adapter = PixivSourceAdapter(
            httpClient = httpClient,
            credentialsProvider = FakeCredentialsProvider().apply { pixivTokens = PixivAuthTokens("access", "refresh", Long.MAX_VALUE) },
            ugoiraMetadataCache = cache,
        )

        val failure = runCatching { adapter.resolveDurationMetadata(ugoira) }.exceptionOrNull()
        val retried = runCatching { adapter.resolveDurationMetadata(ugoira) }

        assertTrue("$failure", failure is SourceAdapterException)
        assertEquals("the retry asks Pixiv again", 2, httpClient.requests.size)
        assertEquals(com.theoriacodex.domain.adapter.DurationMetadataSourceResult.Known(100L), retried.getOrNull())
        assertEquals(body, cache.get("12345"))
    }

    @Test
    fun `a failed duration lookup propagates its failure so the engine can retry (ADR 0046)`() = runTest {
        val credentials = FakeCredentialsProvider().apply {
            pixivTokens = PixivAuthTokens("access", "refresh", Long.MAX_VALUE)
        }
        val answers = listOf<() -> SourceHttpResponse>(
            { SourceHttpResponse(503, "") },
            { throw IOException("reset") },
            { SourceHttpResponse(200, "<html>busy</html>") },
        )
        answers.forEachIndexed { index, answer ->
            var requests = 0
            val httpClient = object : SourceHttpClient {
                override suspend fun get(url: String, query: Map<String, String>, headers: Map<String, String>): SourceHttpResponse {
                    requests += 1
                    return answer()
                }

                override suspend fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): SourceHttpResponse =
                    error("unused")
            }
            val adapter = PixivSourceAdapter(httpClient = httpClient, credentialsProvider = credentials, ugoiraMetadataCache = PixivUgoiraMetadataCache())

            val failure = runCatching { adapter.resolveDurationMetadata(ugoira) }.exceptionOrNull()

            assertTrue("answer $index: $failure", failure is SourceAdapterException)
            assertEquals(1, requests)
        }
    }
}
