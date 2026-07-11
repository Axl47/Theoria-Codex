package com.theoriacodex.app.sourceauth

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class PixivPkceSession(
    val state: String,
    val codeVerifier: String,
    val createdAtEpochMs: Long,
) {
    override fun toString(): String = "PixivPkceSession([REDACTED])"
}

internal interface PixivPkceSessionStore {
    suspend fun read(): PixivPkceSession?

    /** Atomically replaces any older authorization attempt and verifies the committed value. */
    suspend fun writeVerified(session: PixivPkceSession)

    /** Removes only the exact session that reached a successful terminal credential save. */
    suspend fun consume(session: PixivPkceSession): Boolean

    suspend fun reset(): Boolean
}

internal class InMemoryPixivPkceSessionStore(
    initialSession: PixivPkceSession? = null,
) : PixivPkceSessionStore {
    private val mutex = Mutex()
    private var session: PixivPkceSession? = initialSession

    override suspend fun read(): PixivPkceSession? = mutex.withLock { session }

    override suspend fun writeVerified(session: PixivPkceSession) {
        validatePixivPkceSession(session)
        mutex.withLock { this.session = session }
    }

    override suspend fun consume(session: PixivPkceSession): Boolean = mutex.withLock {
        if (this.session != session) return@withLock false
        this.session = null
        true
    }

    override suspend fun reset(): Boolean = mutex.withLock {
        session = null
        true
    }
}

internal object PixivPkceSessionPolicy {
    fun requireAuthorizationCode(
        session: PixivPkceSession,
        callbackState: String?,
        authorizationCode: String?,
        authorizationError: String?,
        nowEpochMs: Long,
        sessionLifetimeMs: Long,
    ): String {
        validatePixivPkceSession(session)
        require(sessionLifetimeMs > 0L) { "Pixiv auth session lifetime must be positive" }

        val ageMs = nowEpochMs - session.createdAtEpochMs
        if (ageMs < 0L || ageMs >= sessionLifetimeMs) {
            throw IllegalStateException("Pixiv authorization session expired")
        }
        if (callbackState.isNullOrBlank() || callbackState != session.state) {
            throw IllegalStateException("Pixiv authorization state mismatch")
        }
        if (!authorizationError.isNullOrBlank()) {
            throw IllegalStateException("Pixiv authorization failed")
        }
        return authorizationCode?.takeIf(String::isNotBlank)
            ?: throw IllegalStateException("Pixiv authorization code missing")
    }
}

internal fun validatePixivPkceSession(session: PixivPkceSession) {
    require(session.state.isNotBlank() && session.state.length <= MAX_PIXIV_PKCE_STATE_LENGTH) {
        "Pixiv authorization state is invalid"
    }
    require(
        session.codeVerifier.length in MIN_PIXIV_PKCE_VERIFIER_LENGTH..MAX_PIXIV_PKCE_VERIFIER_LENGTH &&
            session.codeVerifier.all { character -> character in PIXIV_PKCE_TOKEN_ALPHABET }
    ) {
        "Pixiv authorization code verifier is invalid"
    }
    require(session.createdAtEpochMs > 0L) { "Pixiv authorization creation time is invalid" }
}

internal val PIXIV_PKCE_TOKEN_ALPHABET: Set<Char> = (
    ('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf('-', '.', '_', '~')
).toSet()

internal const val DEFAULT_PIXIV_PKCE_SESSION_LIFETIME_MS = 10L * 60L * 1_000L
private const val MAX_PIXIV_PKCE_STATE_LENGTH = 128
private const val MIN_PIXIV_PKCE_VERIFIER_LENGTH = 43
private const val MAX_PIXIV_PKCE_VERIFIER_LENGTH = 128
