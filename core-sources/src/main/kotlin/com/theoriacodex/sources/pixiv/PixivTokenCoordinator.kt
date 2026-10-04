package com.theoriacodex.sources.pixiv

import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.sources.credentials.PixivAuthTokens
import com.theoriacodex.sources.credentials.PixivCredentialSession
import com.theoriacodex.sources.credentials.SourceCredentialsProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PixivTokenCoordinator(
    private val credentialsProvider: SourceCredentialsProvider,
    authApi: PixivAuthApi,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val refresh: suspend (String) -> PixivAuthTokens = authApi::refresh,
) {
    private val refreshMutex = Mutex()

    suspend fun activeTokens(): PixivAuthTokens {
        val current = requiredTokens()
        if (isUsable(current)) return current
        return refreshMutex.withLock {
            val session = credentialsProvider.getPixivSession()
            val latest = requireTokens(session.tokens)
            if (isUsable(latest)) latest else refreshAndPersist(session)
        }
    }

    suspend fun refreshAfterAuthFailure(failed: PixivAuthTokens): PixivAuthTokens {
        return refreshMutex.withLock {
            val session = credentialsProvider.getPixivSession()
            val latest = requireTokens(session.tokens)
            if (latest.accessToken != failed.accessToken) {
                latest
            } else {
                refreshAndPersist(session)
            }
        }
    }

    private suspend fun requiredTokens(): PixivAuthTokens {
        return requireTokens(credentialsProvider.getPixivTokens())
    }

    private fun requireTokens(tokens: PixivAuthTokens?): PixivAuthTokens =
        tokens ?: throw SourceAdapterException(
                reason = SourceFailureReason.AUTH_REQUIRED,
                message = "Pixiv credentials not configured",
            )

    private fun isUsable(tokens: PixivAuthTokens): Boolean {
        return clock() + PIXIV_TOKEN_EXPIRY_SKEW_MS < tokens.expiresAtEpochMs
    }

    private suspend fun refreshAndPersist(session: PixivCredentialSession): PixivAuthTokens {
        val refreshed = try {
            refresh(requireTokens(session.tokens).refreshToken)
        } catch (failure: SourceAdapterException) {
            if (failure.reason == SourceFailureReason.AUTH_EXPIRED || failure.reason == SourceFailureReason.AUTH_REQUIRED) {
                credentialsProvider.commitPixivRefresh(session, null)
            }
            throw failure
        }
        if (!credentialsProvider.commitPixivRefresh(session, refreshed)) {
            throw SourceAdapterException(SourceFailureReason.AUTH_REQUIRED, "Pixiv account changed; try again")
        }
        return refreshed
    }
}

private const val PIXIV_TOKEN_EXPIRY_SKEW_MS = 60_000L
