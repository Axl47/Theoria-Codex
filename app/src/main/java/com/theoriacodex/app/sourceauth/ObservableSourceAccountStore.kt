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
        return delegate.getPixivTokens().also { synchronizeRecoveryState() }
    }

    override suspend fun savePixivTokens(tokens: PixivAuthTokens) {
        delegate.savePixivTokens(tokens)
        synchronizeRecoveryState()
    }

    override suspend fun clearPixivTokens() {
        delegate.clearPixivTokens()
        synchronizeRecoveryState()
    }

    override suspend fun getGelbooruCredentials(): GelbooruCredentials? {
        return delegate.getGelbooruCredentials().also { synchronizeRecoveryState() }
    }

    override suspend fun saveGelbooruCredentials(credentials: GelbooruCredentials) {
        delegate.saveGelbooruCredentials(credentials)
        synchronizeRecoveryState()
    }

    override suspend fun clearGelbooruCredentials() {
        delegate.clearGelbooruCredentials()
        synchronizeRecoveryState()
    }

    override suspend fun getRule34XxxCredentials(): Rule34XxxCredentials? {
        return delegate.getRule34XxxCredentials().also { credentials ->
            publishRule34XxxAvailability(credentials != null)
        }
    }

    override suspend fun saveRule34XxxCredentials(credentials: Rule34XxxCredentials) {
        delegate.saveRule34XxxCredentials(credentials)
        publishRule34XxxAvailability(configured = true)
    }

    override suspend fun clearRule34XxxCredentials() {
        delegate.clearRule34XxxCredentials()
        publishRule34XxxAvailability(configured = false)
    }

    override suspend fun resetAfterReconnectRequired(): Boolean {
        return delegate.resetAfterReconnectRequired().also { reset ->
            if (reset) {
                publishRule34XxxAvailability(configured = false)
            }
        }
    }

    private fun publishRule34XxxAvailability(configured: Boolean) {
        mutableAvailableSources.value = exposedRealSources(
            rule34XxxConfigured = configured && recoveryState.value == CredentialStoreRecoveryState.Ready,
        )
    }

    private fun synchronizeRecoveryState() {
        if (recoveryState.value == CredentialStoreRecoveryState.ReconnectRequired) {
            publishRule34XxxAvailability(configured = false)
        }
    }
}
