package com.theoriacodex.app.sourceauth

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PixivPkceSessionStoreTest {
    @Test
    fun `callback requires the exact nonblank stored state`() {
        val session = session()

        assertEquals(
            "authorization-code",
            PixivPkceSessionPolicy.requireAuthorizationCode(
                session = session,
                callbackState = session.state,
                authorizationCode = "authorization-code",
                authorizationError = null,
                nowEpochMs = session.createdAtEpochMs + 1L,
                sessionLifetimeMs = DEFAULT_PIXIV_PKCE_SESSION_LIFETIME_MS,
            ),
        )

        listOf(null, "", "other-state").forEach { callbackState ->
            val failure = runCatching {
                PixivPkceSessionPolicy.requireAuthorizationCode(
                    session = session,
                    callbackState = callbackState,
                    authorizationCode = "authorization-code",
                    authorizationError = null,
                    nowEpochMs = session.createdAtEpochMs + 1L,
                    sessionLifetimeMs = DEFAULT_PIXIV_PKCE_SESSION_LIFETIME_MS,
                )
            }.exceptionOrNull()
            assertEquals("Pixiv authorization state mismatch", failure?.message)
        }
    }

    @Test
    fun `provider native callback may omit state but still rejects a conflicting state`() {
        val session = session()

        listOf(null, "").forEach { callbackState ->
            assertEquals(
                "authorization-code",
                PixivPkceSessionPolicy.requireAuthorizationCode(
                    session = session,
                    callbackState = callbackState,
                    authorizationCode = "authorization-code",
                    authorizationError = null,
                    nowEpochMs = session.createdAtEpochMs + 1L,
                    sessionLifetimeMs = DEFAULT_PIXIV_PKCE_SESSION_LIFETIME_MS,
                    allowMissingCallbackState = true,
                ),
            )
        }

        val mismatch = runCatching {
            PixivPkceSessionPolicy.requireAuthorizationCode(
                session = session,
                callbackState = "other-state",
                authorizationCode = "authorization-code",
                authorizationError = null,
                nowEpochMs = session.createdAtEpochMs + 1L,
                sessionLifetimeMs = DEFAULT_PIXIV_PKCE_SESSION_LIFETIME_MS,
                allowMissingCallbackState = true,
            )
        }.exceptionOrNull()

        assertEquals("Pixiv authorization state mismatch", mismatch?.message)
    }

    @Test
    fun `expired and future dated sessions fail before provider values are accepted`() {
        val session = session()

        val expired = callbackFailure(
            session = session,
            nowEpochMs = session.createdAtEpochMs + DEFAULT_PIXIV_PKCE_SESSION_LIFETIME_MS,
        )
        val futureDated = callbackFailure(
            session = session,
            nowEpochMs = session.createdAtEpochMs - 1L,
        )

        assertEquals("Pixiv authorization session expired", expired.message)
        assertEquals("Pixiv authorization session expired", futureDated.message)
    }

    @Test
    fun `provider errors and missing codes remain terminal validation failures`() {
        val session = session()
        val providerFailure = runCatching {
            PixivPkceSessionPolicy.requireAuthorizationCode(
                session = session,
                callbackState = session.state,
                authorizationCode = null,
                authorizationError = "access_denied",
                nowEpochMs = session.createdAtEpochMs + 1L,
                sessionLifetimeMs = DEFAULT_PIXIV_PKCE_SESSION_LIFETIME_MS,
            )
        }.exceptionOrNull()
        val missingCode = callbackFailure(session = session)

        assertEquals("Pixiv authorization failed", providerFailure?.message)
        assertEquals("Pixiv authorization code missing", missingCode.message)
    }

    @Test
    fun `in memory store consumes only the exact successfully completed session`() = runTest {
        val first = session(state = "first-state")
        val replacement = session(state = "replacement-state")
        val store = InMemoryPixivPkceSessionStore()

        store.writeVerified(first)
        assertFalse(store.consume(replacement))
        assertEquals(first, store.read())

        store.writeVerified(replacement)
        assertFalse(store.consume(first))
        assertTrue(store.consume(replacement))
        assertNull(store.read())
        assertFalse("a replay cannot consume an absent session", store.consume(replacement))
    }

    @Test
    fun `session diagnostics never expose state or verifier`() {
        val session = session()

        assertFalse(session.toString().contains(session.state))
        assertFalse(session.toString().contains(session.codeVerifier))
    }

    private fun callbackFailure(
        session: PixivPkceSession,
        nowEpochMs: Long = session.createdAtEpochMs + 1L,
    ): Throwable {
        return checkNotNull(
            runCatching {
                PixivPkceSessionPolicy.requireAuthorizationCode(
                    session = session,
                    callbackState = session.state,
                    authorizationCode = null,
                    authorizationError = null,
                    nowEpochMs = nowEpochMs,
                    sessionLifetimeMs = DEFAULT_PIXIV_PKCE_SESSION_LIFETIME_MS,
                )
            }.exceptionOrNull()
        )
    }

    private fun session(
        state: String = "stored-state",
        createdAtEpochMs: Long = 1_000_000L,
    ): PixivPkceSession {
        return PixivPkceSession(
            state = state,
            codeVerifier = "v".repeat(64),
            createdAtEpochMs = createdAtEpochMs,
        )
    }
}
