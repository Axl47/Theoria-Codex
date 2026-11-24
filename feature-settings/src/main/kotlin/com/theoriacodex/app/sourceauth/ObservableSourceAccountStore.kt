package com.theoriacodex.app.sourceauth

import com.theoriacodex.app.source.exposedRealSources
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.credentials.UserApiKey
import com.theoriacodex.sources.credentials.PixivAuthTokens
import com.theoriacodex.sources.credentials.PixivCredentialSession
import com.theoriacodex.sources.credentials.SessionBoundSourceCredentials
import com.theoriacodex.sources.credentials.SourceCredentialsProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface RecoverableSourceCredentialsStore : SourceCredentialsProvider {
    val recoveryState: StateFlow<CredentialStoreRecoveryState>

    suspend fun resetAfterReconnectRequired(): Boolean
}

interface SourceAccountStore : RecoverableSourceCredentialsStore {
    val availableSources: StateFlow<Set<SourceKey>>

    suspend fun refreshAvailability()
}

class ObservableSourceAccountStore(
    private val delegate: RecoverableSourceCredentialsStore,
) : SourceAccountStore {
    private val pixivSessions = SessionBoundSourceCredentials(delegate)
    private val mutableAvailableSources = MutableStateFlow(exposedRealSources(rule34XxxConfigured = false))

    override val recoveryState: StateFlow<CredentialStoreRecoveryState> = delegate.recoveryState
    override val availableSources: StateFlow<Set<SourceKey>> = mutableAvailableSources.asStateFlow()

    override suspend fun refreshAvailability() {
        userApiKey(SourceKey.RULE34XXX)
    }

    override suspend fun getPixivTokens(): PixivAuthTokens? {
        return withRecoverySynchronization { pixivSessions.getPixivTokens() }
    }

    override suspend fun savePixivTokens(tokens: PixivAuthTokens) {
        withRecoverySynchronization { pixivSessions.savePixivTokens(tokens) }
    }

    override suspend fun clearPixivTokens() {
        withRecoverySynchronization { pixivSessions.clearPixivTokens() }
    }

    override suspend fun getPixivSession(): PixivCredentialSession =
        withRecoverySynchronization { pixivSessions.getPixivSession() }

    override suspend fun commitPixivRefresh(session: PixivCredentialSession, tokens: PixivAuthTokens?): Boolean =
        withRecoverySynchronization { pixivSessions.commitPixivRefresh(session, tokens) }

    override suspend fun userApiKey(source: SourceKey): UserApiKey? {
        val key = withRecoverySynchronization { delegate.userApiKey(source) }
        if (source == SourceKey.RULE34XXX) publishRule34XxxAvailability(key != null)
        return key
    }

    override suspend fun saveUserApiKey(source: SourceKey, key: UserApiKey) {
        withRecoverySynchronization { delegate.saveUserApiKey(source, key) }
        if (source == SourceKey.RULE34XXX) publishRule34XxxAvailability(configured = true)
    }

    override suspend fun clearUserApiKey(source: SourceKey) {
        withRecoverySynchronization { delegate.clearUserApiKey(source) }
        if (source == SourceKey.RULE34XXX) publishRule34XxxAvailability(configured = false)
    }

    override suspend fun resetAfterReconnectRequired(): Boolean {
        val reset = withRecoverySynchronization {
            pixivSessions.invalidatePixivSession { delegate.resetAfterReconnectRequired() }
        }
        if (reset) {
            publishRule34XxxAvailability(configured = false)
        }
        return reset
    }

    private fun publishRule34XxxAvailability(configured: Boolean) {
        mutableAvailableSources.value = exposedRealSources(
            rule34XxxConfigured = configured && recoveryState.value == CredentialStoreRecoveryState.Ready,
        )
    }

    private suspend fun <T> withRecoverySynchronization(action: suspend () -> T): T {
        return try {
            action()
        } finally {
            synchronizeRecoveryState()
        }
    }

    private fun synchronizeRecoveryState() {
        if (recoveryState.value != CredentialStoreRecoveryState.Ready) {
            publishRule34XxxAvailability(configured = false)
        }
    }
}
