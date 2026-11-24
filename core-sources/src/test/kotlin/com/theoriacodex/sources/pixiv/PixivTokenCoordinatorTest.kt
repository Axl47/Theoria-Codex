package com.theoriacodex.sources.pixiv

import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.sources.credentials.PixivAuthTokens
import com.theoriacodex.sources.credentials.SourceCredentialsProvider
import com.theoriacodex.sources.credentials.SessionBoundSourceCredentials
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.http.SourceHttpResponse
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
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
            credentialsProvider = SessionBoundSourceCredentials(credentials),
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
    fun `auth failure reuses token persisted by concurrent winner (ADR 0046)`() = runTest {
        val failed = tokens("failed", expiresAt = 1_000_000L)
        val winner = tokens("winner", expiresAt = 2_000_000L)
        val credentials = FakeCredentials(failed)
        val refreshCalls = AtomicInteger()
        val firstEntered = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val coordinator = PixivTokenCoordinator(
            credentialsProvider = SessionBoundSourceCredentials(credentials),
            authApi = PixivAuthApi(UnusedHttpClient),
            clock = { 100_000L },
            refresh = {
                if (refreshCalls.incrementAndGet() == 1) {
                    firstEntered.complete(Unit)
                    withTimeoutOrNull(SECOND_CALLER_GRACE_MS) { secondEntered.await() }
                } else {
                    secondEntered.complete(Unit)
                }
                winner
            },
        )

        val first = async { runCatching { coordinator.refreshAfterAuthFailure(failed) } }
        firstEntered.await()
        val second = async { runCatching { coordinator.refreshAfterAuthFailure(failed) } }

        val results = listOf(first, second).map { it.await() }

        assertEquals(1, refreshCalls.get())
        assertEquals(listOf(winner, winner), results.map { it.getOrThrow() })
        assertEquals(winner, credentials.pixiv)
    }

    @Test
    fun `a provider that cannot commit refreshes fails the refresh instead of using its tokens (ADR 0046)`() = runTest {
        val expired = tokens("old", expiresAt = 1L)
        val credentials = FakeCredentials(expired)
        val coordinator = PixivTokenCoordinator(
            credentialsProvider = credentials,
            authApi = PixivAuthApi(UnusedHttpClient),
            clock = { 100_000L },
            refresh = { tokens("new", expiresAt = 1_000_000L) },
        )

        val failure = runCatching { coordinator.activeTokens() }.exceptionOrNull()

        assertEquals(SourceFailureReason.AUTH_REQUIRED, (failure as? SourceAdapterException)?.reason)
        assertEquals(expired, credentials.pixiv)
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
    }

    private companion object {
        const val SECOND_CALLER_GRACE_MS = 2_000L
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
