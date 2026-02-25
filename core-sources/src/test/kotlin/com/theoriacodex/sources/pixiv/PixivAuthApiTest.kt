package com.theoriacodex.sources.pixiv

import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.sources.http.SourceHttpResponse
import com.theoriacodex.sources.testing.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PixivAuthApiTest {
    @Test
    fun `authorization url defaults to pixiv app flow parameters`() {
        val authApi = PixivAuthApi(httpClient = FakeHttpClient())

        val url = authApi.buildAuthorizationUrl(codeChallenge = "challenge-1")

        assertTrue(url.startsWith("https://app-api.pixiv.net/web/v1/login?"))
        assertTrue(url.contains("code_challenge=challenge-1"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("client=pixiv-android"))
        assertFalse(url.contains("redirect_uri="))
    }

    @Test
    fun `exchange parses tokens and computes expiry`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextPostResponse = SourceHttpResponse(
                statusCode = 200,
                body = """{"access_token":"a","refresh_token":"r","expires_in":3600}""",
            )
        }
        val authApi = PixivAuthApi(
            httpClient = httpClient,
            clock = { 1_000L },
        )

        val tokens = authApi.exchangeAuthorizationCode(
            code = "code-1",
            codeVerifier = "verifier-1",
            redirectUri = DEFAULT_PIXIV_REDIRECT_URI,
        )

        assertEquals("a", tokens.accessToken)
        assertEquals("r", tokens.refreshToken)
        assertEquals(3_601_000L, tokens.expiresAtEpochMs)
        assertNotNull(httpClient.lastPost)
        assertEquals(
            DEFAULT_PIXIV_REDIRECT_URI,
            requireNotNull(httpClient.lastPost).form["redirect_uri"],
        )
    }

    @Test
    fun `refresh maps unauthorized to auth expired failure`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextPostResponse = SourceHttpResponse(statusCode = 401, body = """{"error":"invalid_grant"}""")
        }
        val authApi = PixivAuthApi(httpClient = httpClient)

        val failure = runCatching { authApi.refresh("refresh-token") }.exceptionOrNull()

        require(failure is SourceAdapterException)
        assertEquals(SourceFailureReason.AUTH_EXPIRED, failure.reason)
    }
}
