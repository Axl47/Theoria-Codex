package com.theoriacodex.sources.pixiv

import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.sources.credentials.PixivAuthTokens
import com.theoriacodex.sources.credentials.SessionBoundSourceCredentials
import com.theoriacodex.sources.testing.FakeCredentialsProvider
import com.theoriacodex.sources.testing.FakeHttpClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PixivAccountDecisionTest {
    @Test fun `disconnect new login and reusing identical credentials all reject the older refresh`() = runTest {
        val old = PixivAuthTokens("old", "old-refresh", 1L)
        val replacement = PixivAuthTokens("new", "new-refresh", 1_000_000L)
        for (next in listOf(null, replacement, old)) {
            val delegate = FakeCredentialsProvider().apply { pixivTokens = old }
            val accounts = SessionBoundSourceCredentials(delegate)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val coordinator = PixivTokenCoordinator(accounts, PixivAuthApi(FakeHttpClient()), clock = { 100L }, refresh = {
                entered.complete(Unit)
                release.await()
                replacement
            })
            val task = async { runCatching { coordinator.activeTokens() } }
            entered.await()
            accounts.clearPixivTokens()
            if (next != null) accounts.savePixivTokens(next)
            release.complete(Unit)
            assertTrue(task.await().isFailure)
            assertEquals(next, accounts.getPixivTokens())
        }
    }

    @Test fun `expired response cannot clear a newer login`() = runTest {
        val delegate = FakeCredentialsProvider().apply { pixivTokens = PixivAuthTokens("old", "old-refresh", 1L) }
        val accounts = SessionBoundSourceCredentials(delegate)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val coordinator = PixivTokenCoordinator(accounts, PixivAuthApi(FakeHttpClient()), clock = { 100L }, refresh = {
            entered.complete(Unit)
            release.await()
            throw SourceAdapterException(SourceFailureReason.AUTH_EXPIRED, "Expired")
        })
        val task = async { runCatching { coordinator.activeTokens() } }
        entered.await()
        val replacement = PixivAuthTokens("new", "new-refresh", 1_000_000L)
        accounts.savePixivTokens(replacement)
        release.complete(Unit)
        assertTrue(task.await().isFailure)
        assertEquals(replacement, accounts.getPixivTokens())
    }
}
