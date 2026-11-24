package com.theoriacodex.app.sourceauth

import android.content.Context
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.source.SiteCredentials
import com.theoriacodex.sources.credentials.PixivAuthTokens
import com.theoriacodex.sources.credentials.SourceCredentialsProvider
import com.theoriacodex.sources.credentials.UserApiKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

class AndroidSecureSourceCredentialsStore private constructor(
    private val delegate: VersionedSourceCredentialsStore,
) : RecoverableSourceCredentialsStore, SiteCredentialsStore by delegate {
    constructor(context: Context) : this(
        VersionedSourceCredentialsStore(
            envelopeStore = AndroidCredentialEnvelopeStore(context.applicationContext),
        ),
    )

    override val recoveryState: StateFlow<CredentialStoreRecoveryState> = delegate.recoveryState

    override suspend fun getPixivTokens(): PixivAuthTokens? = delegate.getPixivTokens()

    override suspend fun savePixivTokens(tokens: PixivAuthTokens) = delegate.savePixivTokens(tokens)

    override suspend fun clearPixivTokens() = delegate.clearPixivTokens()

    override suspend fun userApiKey(source: SourceKey): UserApiKey? = delegate.userApiKey(source)

    override suspend fun saveUserApiKey(source: SourceKey, key: UserApiKey) = delegate.saveUserApiKey(source, key)

    override suspend fun clearUserApiKey(source: SourceKey) = delegate.clearUserApiKey(source)

    override suspend fun resetAfterReconnectRequired(): Boolean =
        delegate.resetAfterReconnectRequired()
}

class InMemorySourceCredentialsStore : SourceCredentialsProvider {
    private var pixivTokens: PixivAuthTokens? = null
    private val userApiKeys = mutableMapOf<SourceKey, UserApiKey>()

    override suspend fun getPixivTokens(): PixivAuthTokens? = pixivTokens

    override suspend fun savePixivTokens(tokens: PixivAuthTokens) {
        pixivTokens = tokens
    }

    override suspend fun clearPixivTokens() {
        pixivTokens = null
    }

    override suspend fun userApiKey(source: SourceKey): UserApiKey? = userApiKeys[source]

    override suspend fun saveUserApiKey(source: SourceKey, key: UserApiKey) {
        userApiKeys[source] = key
    }

    override suspend fun clearUserApiKey(source: SourceKey) {
        userApiKeys -= source
    }
}

interface SiteCredentialsStore {
    suspend fun get(sourceId: String): SiteCredentials?
    suspend fun save(sourceId: String, credentials: SiteCredentials)
    suspend fun clear(sourceId: String)

    val changes: Flow<Unit>
}

class InMemorySiteCredentialsStore : SiteCredentialsStore {
    private val stored = MutableStateFlow<Map<String, SiteCredentials>>(emptyMap())

    override suspend fun get(sourceId: String): SiteCredentials? = stored.value[sourceId]

    override suspend fun save(sourceId: String, credentials: SiteCredentials) {
        stored.update { it + (sourceId to credentials) }
    }

    override suspend fun clear(sourceId: String) {
        stored.update { it - sourceId }
    }

    override val changes: Flow<Unit> = stored.map { }
}
