package com.theoriacodex.sources.credentials

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes account decisions and refresh commits without holding the lock during provider I/O. */
class SessionBoundSourceCredentials(private val delegate: SourceCredentialsProvider) : SourceCredentialsProvider by delegate {
    private val mutex = Mutex()
    private var revision = 0L

    override suspend fun getPixivTokens(): PixivAuthTokens? = mutex.withLock { delegate.getPixivTokens() }
    override suspend fun getPixivSession(): PixivCredentialSession = mutex.withLock {
        PixivCredentialSession(delegate.getPixivTokens(), revision)
    }

    override suspend fun savePixivTokens(tokens: PixivAuthTokens) = invalidatePixivSession { delegate.savePixivTokens(tokens) }
    override suspend fun clearPixivTokens() = invalidatePixivSession { delegate.clearPixivTokens() }

    override suspend fun commitPixivRefresh(session: PixivCredentialSession, tokens: PixivAuthTokens?): Boolean = mutex.withLock {
        if (session.revision != revision || session.tokens != delegate.getPixivTokens()) return@withLock false
        revision++
        if (tokens == null) delegate.clearPixivTokens() else delegate.savePixivTokens(tokens)
        true
    }

    /** Resetting encrypted storage is also an account decision, even if it fails to persist. */
    suspend fun <T> invalidatePixivSession(action: suspend () -> T): T = mutex.withLock {
        revision++
        action()
    }
}
