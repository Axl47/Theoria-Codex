package com.theoriacodex.sources.pixiv

import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.sources.credentials.PixivAuthTokens
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
            val latest = requiredTokens()
            if (isUsable(latest)) latest else refreshAndPersist(latest.refreshToken)
        }
    }

    suspend fun refreshAfterAuthFailure(failed: PixivAuthTokens): PixivAuthTokens {
        return refreshMutex.withLock {
            val latest = requiredTokens()
            if (latest.accessToken != failed.accessToken) {
                latest
            } else {
                refreshAndPersist(latest.refreshToken)
            }
        }
    }

    private suspend fun requiredTokens(): PixivAuthTokens {
        return credentialsProvider.getPixivTokens()
            ?: throw SourceAdapterException(
                reason = SourceFailureReason.AUTH_REQUIRED,
                message = "Pixiv credentials not configured",
            )
    }

    private fun isUsable(tokens: PixivAuthTokens): Boolean {
        return clock() + PIXIV_TOKEN_EXPIRY_SKEW_MS < tokens.expiresAtEpochMs
    }

    private suspend fun refreshAndPersist(refreshToken: String): PixivAuthTokens {
        val refreshed = refresh(refreshToken)
        credentialsProvider.savePixivTokens(refreshed)
        return refreshed
    }
}

private const val PIXIV_TOKEN_EXPIRY_SKEW_MS = 60_000L
