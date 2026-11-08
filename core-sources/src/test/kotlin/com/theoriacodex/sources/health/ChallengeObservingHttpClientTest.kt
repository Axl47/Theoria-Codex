package com.theoriacodex.sources.health

import com.theoriacodex.sources.http.SourceByteRange
import com.theoriacodex.sources.http.SourceByteResponse
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.http.SourceHttpResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChallengeObservingHttpClientTest {
    @Test
    fun `cloudflare challenge header and challenge pages are browser challenges`() {
        val header = SourceHttpResponse(403, "", mapOf("cf-mitigated" to listOf("challenge")))
        val page = SourceHttpResponse(403, "<!doctype html><title>Just a moment</title>/cdn-cgi/challenge-platform/")

        assertTrue(header.isBrowserChallenge())
        assertTrue(page.isBrowserChallenge())
    }

    @Test
    fun `maintenance pages and plain refusals are not browser challenges`() {
        val maintenance = SourceHttpResponse(503, "<!doctype html><h1>Down for maintenance</h1>")
        val forbidden = SourceHttpResponse(403, """{"message":"forbidden"}""")

        assertFalse(maintenance.isBrowserChallenge())
        assertFalse(forbidden.isBrowserChallenge())
    }

    @Test
    fun `counts only the challenges it passes through on every transport`() = runTest {
        val cleared = SourceHttpResponse(200, "{}")
        val challenge = SourceHttpResponse(403, "", mapOf("CF-Mitigated" to listOf("challenge")))
        val texts = ArrayDeque(listOf(cleared, challenge, challenge, challenge))
        val client = ChallengeObservingHttpClient(
            object : SourceHttpClient {
                override suspend fun get(
                    url: String,
                    query: Map<String, String>,
                    headers: Map<String, String>,
                ) = texts.removeFirst()

                override suspend fun postForm(
                    url: String,
                    form: Map<String, String>,
                    headers: Map<String, String>,
                ) = texts.removeFirst()

                override suspend fun postJson(
                    url: String,
                    body: String,
                    headers: Map<String, String>,
                ) = texts.removeFirst()

                override suspend fun getBytes(
                    url: String,
                    query: Map<String, String>,
                    headers: Map<String, String>,
                    range: SourceByteRange?,
                    maxBodyBytes: Int,
                ) = SourceByteResponse(challenge.statusCode, ByteArray(0), challenge.headers)
            },
        )

        client.get("https://example.test")
        assertEquals(0, client.challengesSeen())
        client.get("https://example.test")
        client.postForm("https://example.test", emptyMap())
        client.postJson("https://example.test", "{}")
        client.getBytes("https://example.test")
        assertEquals(4, client.challengesSeen())
    }
}
