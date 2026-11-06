package com.theoriacodex.sources.pixiv

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.PIXIV_UGOIRA_MIME
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.credentials.PixivAuthTokens
import com.theoriacodex.sources.http.SourceHttpResponse
import com.theoriacodex.sources.testing.FakeCredentialsProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PixivRequestPacingTest {
    @Test
    fun `background duration lookups do not delay the next browse request`() = runTest {
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
        )
        val ugoira = Post(
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

        adapter.resolveDurationMetadata(ugoira)
        adapter.relatedPosts(PostId(SourceKey.PIXIV, "10"), 30)

        assertEquals(0L, testScheduler.currentTime)
        assertEquals(2, httpClient.requests.size)
    }

    @Test
    fun `duration lookups reuse ugoira metadata another consumer already fetched`() = runTest {
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
        val ugoira = Post(
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

        assertEquals(
            com.theoriacodex.domain.adapter.DurationMetadataSourceResult.Known(200L),
            adapter.resolveDurationMetadata(ugoira),
        )
        assertEquals(0, httpClient.requests.size)
    }
}
