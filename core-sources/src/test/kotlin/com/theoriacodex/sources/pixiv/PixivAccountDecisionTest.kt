package com.theoriacodex.sources.pixiv

import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.sources.credentials.PixivAuthTokens
import com.theoriacodex.sources.credentials.PixivCredentialSession
import com.theoriacodex.sources.credentials.SessionBoundSourceCredentials
import com.theoriacodex.sources.testing.FakeCredentialsProvider
import com.theoriacodex.sources.testing.FakeHttpClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PixivAccountDecisionTest {
    @Test fun `disconnect new login and reusing identical credentials all reject the older refresh (ADR 0046)`() = runTest {
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

    @Test fun `expired response cannot clear a newer login (ADR 0046)`() = runTest {
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

    @Test fun `a reset that fails to persist still rejects the older refresh (ADR 0046)`() = runTest {
        val old = PixivAuthTokens("old", "old-refresh", 1L)
        val accounts = SessionBoundSourceCredentials(FakeCredentialsProvider().apply { pixivTokens = old })
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val coordinator = PixivTokenCoordinator(accounts, PixivAuthApi(FakeHttpClient()), clock = { 100L }, refresh = {
            entered.complete(Unit)
            release.await()
            PixivAuthTokens("new", "new-refresh", 1_000_000L)
        })
        val task = async { runCatching { coordinator.activeTokens() } }
        entered.await()
        val reset = runCatching { accounts.invalidatePixivSession { error("storage unavailable") } }
        release.complete(Unit)

        assertTrue(reset.isFailure)
        assertTrue(task.await().isFailure)
        assertEquals(old, accounts.getPixivTokens())
    }

    @Test fun `saving the same tokens again still rejects a refresh that started before it (ADR 0046)`() = runTest {
        val old = PixivAuthTokens("old", "old-refresh", 1L)
        val accounts = SessionBoundSourceCredentials(FakeCredentialsProvider().apply { pixivTokens = old })
        val session = accounts.getPixivSession()

        accounts.savePixivTokens(old)

        assertFalse(accounts.commitPixivRefresh(session, PixivAuthTokens("new", "new-refresh", 1_000_000L)))
        assertEquals(old, accounts.getPixivTokens())
    }

    @Test fun `an account decision and a refresh commit never run inside each other (ADR 0046)`() = runTest {
        val old = PixivAuthTokens("old", "old-refresh", 1L)
        val refreshed = PixivAuthTokens("new", "new-refresh", 1_000_000L)
        val delegate = FakeCredentialsProvider().apply { pixivTokens = old }
        val saveGate = CompletableDeferred<Unit>()
        val accounts = SessionBoundSourceCredentials(object : com.theoriacodex.sources.credentials.SourceCredentialsProvider by delegate {
            override suspend fun savePixivTokens(tokens: PixivAuthTokens) {
                saveGate.await()
                delegate.savePixivTokens(tokens)
            }

            override suspend fun commitPixivRefresh(session: PixivCredentialSession, tokens: PixivAuthTokens?): Boolean =
                error("SessionBoundSourceCredentials commits itself")
        })

        val commit = async { accounts.commitPixivRefresh(accounts.getPixivSession(), refreshed) }
        runCurrent()
        val decisionDuringCommit = async { accounts.invalidatePixivSession { "decided" } }
        runCurrent()
        assertFalse("an account decision ran inside a refresh commit", decisionDuringCommit.isCompleted)
        saveGate.complete(Unit)
        assertTrue(commit.await())
        assertEquals("decided", decisionDuringCommit.await())

        val decisionGate = CompletableDeferred<Unit>()
        val staleSession = accounts.getPixivSession()
        val decision = async { accounts.invalidatePixivSession { decisionGate.await() } }
        runCurrent()
        val commitDuringDecision = async { accounts.commitPixivRefresh(staleSession, old) }
        runCurrent()
        assertFalse("a refresh commit ran inside an account decision", commitDuringDecision.isCompleted)
        decisionGate.complete(Unit)
        decision.await()
        assertFalse(commitDuringDecision.await())
        assertEquals(refreshed, accounts.getPixivTokens())
    }
}
