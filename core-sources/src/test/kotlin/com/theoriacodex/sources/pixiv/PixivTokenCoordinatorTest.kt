package com.theoriacodex.sources.pixiv

import com.theoriacodex.sources.credentials.GelbooruCredentials
import com.theoriacodex.sources.credentials.PixivAuthTokens
import com.theoriacodex.sources.credentials.Rule34XxxCredentials
import com.theoriacodex.sources.credentials.SourceCredentialsProvider
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.http.SourceHttpResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PixivTokenCoordinatorTest {
    @Test
    fun `concurrent expired-token callers perform one refresh and share persisted winner`() = runTest {
        val expired = tokens("old", expiresAt = 1L)
        val refreshed = tokens("new", expiresAt = 1_000_000L)
        val credentials = FakeCredentials(expired)
        var refreshCount = 0
        val coordinator = PixivTokenCoordinator(
            credentialsProvider = credentials,
            authApi = PixivAuthApi(UnusedHttpClient),
            clock = { 100_000L },
            refresh = {
                refreshCount += 1
                refreshed
            },
        )

        val results = List(8) { async { coordinator.activeTokens() } }.awaitAll()

        assertEquals(List(8) { refreshed }, results)
        assertEquals(1, refreshCount)
        assertEquals(refreshed, credentials.pixiv)
    }

    @Test
    fun `auth failure reuses token persisted by concurrent winner`() = runTest {
        val failed = tokens("failed", expiresAt = 1_000_000L)
        val winner = tokens("winner", expiresAt = 2_000_000L)
        val credentials = FakeCredentials(failed)
        var refreshCount = 0
        val coordinator = PixivTokenCoordinator(
            credentialsProvider = credentials,
            authApi = PixivAuthApi(UnusedHttpClient),
            clock = { 100_000L },
            refresh = {
                refreshCount += 1
                winner
            },
        )

        val results = List(6) { async { coordinator.refreshAfterAuthFailure(failed) } }.awaitAll()

        assertEquals(List(6) { winner }, results)
        assertEquals(1, refreshCount)
    }

    private fun tokens(access: String, expiresAt: Long) = PixivAuthTokens(
        accessToken = access,
        refreshToken = "refresh-$access",
        expiresAtEpochMs = expiresAt,
    )

    private class FakeCredentials(
        var pixiv: PixivAuthTokens?,
    ) : SourceCredentialsProvider {
        override suspend fun getPixivTokens(): PixivAuthTokens? = pixiv
        override suspend fun savePixivTokens(tokens: PixivAuthTokens) {
            pixiv = tokens
        }
        override suspend fun clearPixivTokens() {
            pixiv = null
        }
        override suspend fun getGelbooruCredentials(): GelbooruCredentials? = null
        override suspend fun saveGelbooruCredentials(credentials: GelbooruCredentials) = Unit
        override suspend fun clearGelbooruCredentials() = Unit
        override suspend fun getRule34XxxCredentials(): Rule34XxxCredentials? = null
        override suspend fun saveRule34XxxCredentials(credentials: Rule34XxxCredentials) = Unit
        override suspend fun clearRule34XxxCredentials() = Unit
    }

    private object UnusedHttpClient : SourceHttpClient {
        override suspend fun get(
            url: String,
            query: Map<String, String>,
            headers: Map<String, String>,
        ): SourceHttpResponse = error("HTTP is not used by the injected refresh function")

        override suspend fun postForm(
            url: String,
            form: Map<String, String>,
            headers: Map<String, String>,
        ): SourceHttpResponse = error("HTTP is not used by the injected refresh function")
    }
}
