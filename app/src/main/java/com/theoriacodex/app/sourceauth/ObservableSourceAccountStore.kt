package com.theoriacodex.app.sourceauth

import com.theoriacodex.app.source.exposedRealSources
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.credentials.GelbooruCredentials
import com.theoriacodex.sources.credentials.PixivAuthTokens
import com.theoriacodex.sources.credentials.Rule34XxxCredentials
import com.theoriacodex.sources.credentials.SourceCredentialsProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The recoverable credential boundary used by the application container.
 *
 * Keeping recovery on the interface lets the app shell respond to encrypted-store failures
 * without depending on the Android storage implementation.
 */
interface RecoverableSourceCredentialsStore : SourceCredentialsProvider {
    val recoveryState: StateFlow<CredentialStoreRecoveryState>

    suspend fun resetAfterReconnectRequired(): Boolean
}

/**
 * Stable account capability state for the lifetime of the application.
 *
 * Credential changes update [availableSources] instead of rebuilding the source registry and
 * every route coordinator that depends on it.
 */
interface SourceAccountStore : RecoverableSourceCredentialsStore {
    val availableSources: StateFlow<Set<SourceKey>>

    suspend fun refreshAvailability()
}

class ObservableSourceAccountStore(
    private val delegate: RecoverableSourceCredentialsStore,
) : SourceAccountStore {
    private val mutableAvailableSources = MutableStateFlow(exposedRealSources(rule34XxxConfigured = false))

    override val recoveryState: StateFlow<CredentialStoreRecoveryState> = delegate.recoveryState
    override val availableSources: StateFlow<Set<SourceKey>> = mutableAvailableSources.asStateFlow()

    override suspend fun refreshAvailability() {
        getRule34XxxCredentials()
    }

    override suspend fun getPixivTokens(): PixivAuthTokens? {
        return withRecoverySynchronization { delegate.getPixivTokens() }
    }

    override suspend fun savePixivTokens(tokens: PixivAuthTokens) {
        withRecoverySynchronization { delegate.savePixivTokens(tokens) }
    }

    override suspend fun clearPixivTokens() {
        withRecoverySynchronization { delegate.clearPixivTokens() }
    }

    override suspend fun getGelbooruCredentials(): GelbooruCredentials? {
        return withRecoverySynchronization { delegate.getGelbooruCredentials() }
    }

    override suspend fun saveGelbooruCredentials(credentials: GelbooruCredentials) {
        withRecoverySynchronization { delegate.saveGelbooruCredentials(credentials) }
    }

    override suspend fun clearGelbooruCredentials() {
        withRecoverySynchronization { delegate.clearGelbooruCredentials() }
    }

    override suspend fun getRule34XxxCredentials(): Rule34XxxCredentials? {
        val credentials = withRecoverySynchronization { delegate.getRule34XxxCredentials() }
        publishRule34XxxAvailability(credentials != null)
        return credentials
    }

    override suspend fun saveRule34XxxCredentials(credentials: Rule34XxxCredentials) {
        withRecoverySynchronization { delegate.saveRule34XxxCredentials(credentials) }
        publishRule34XxxAvailability(configured = true)
    }

    override suspend fun clearRule34XxxCredentials() {
        withRecoverySynchronization { delegate.clearRule34XxxCredentials() }
        publishRule34XxxAvailability(configured = false)
    }

    override suspend fun resetAfterReconnectRequired(): Boolean {
        val reset = withRecoverySynchronization { delegate.resetAfterReconnectRequired() }
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
