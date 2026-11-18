package com.theoriacodex.app.sourceauth

import android.content.Context
import com.theoriacodex.domain.source.SiteCredentials
import com.theoriacodex.sources.credentials.GelbooruCredentials
import com.theoriacodex.sources.credentials.PixivAuthTokens
import com.theoriacodex.sources.credentials.Rule34XxxCredentials
import com.theoriacodex.sources.credentials.SourceCredentialsProvider
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

    override suspend fun getGelbooruCredentials(): GelbooruCredentials? = delegate.getGelbooruCredentials()

    override suspend fun saveGelbooruCredentials(credentials: GelbooruCredentials) =
        delegate.saveGelbooruCredentials(credentials)

    override suspend fun clearGelbooruCredentials() = delegate.clearGelbooruCredentials()

    override suspend fun getRule34XxxCredentials(): Rule34XxxCredentials? =
        delegate.getRule34XxxCredentials()

    override suspend fun saveRule34XxxCredentials(credentials: Rule34XxxCredentials) =
        delegate.saveRule34XxxCredentials(credentials)

    override suspend fun clearRule34XxxCredentials() = delegate.clearRule34XxxCredentials()

    /**
     * Clears an unreadable encrypted store only after the user has chosen to reconnect sources.
     * Ordinary startup and reads never delete credential material automatically.
     */
    override suspend fun resetAfterReconnectRequired(): Boolean =
        delegate.resetAfterReconnectRequired()
}

class InMemorySourceCredentialsStore : SourceCredentialsProvider {
    private var pixivTokens: PixivAuthTokens? = null
    private var gelbooruCredentials: GelbooruCredentials? = null
    private var rule34XxxCredentials: Rule34XxxCredentials? = null

    override suspend fun getPixivTokens(): PixivAuthTokens? = pixivTokens

    override suspend fun savePixivTokens(tokens: PixivAuthTokens) {
        pixivTokens = tokens
    }

    override suspend fun clearPixivTokens() {
        pixivTokens = null
    }

    override suspend fun getGelbooruCredentials(): GelbooruCredentials? = gelbooruCredentials

    override suspend fun saveGelbooruCredentials(credentials: GelbooruCredentials) {
        gelbooruCredentials = credentials
    }

    override suspend fun clearGelbooruCredentials() {
        gelbooruCredentials = null
    }

    override suspend fun getRule34XxxCredentials(): Rule34XxxCredentials? = rule34XxxCredentials

    override suspend fun saveRule34XxxCredentials(credentials: Rule34XxxCredentials) {
        rule34XxxCredentials = credentials
    }

    override suspend fun clearRule34XxxCredentials() {
        rule34XxxCredentials = null
    }
}

/**
 * Keys for user-added sites, by source id (plan C6). Stored in the encrypted credential payload's
 * additive `customSites` map, never in the site profile or a backup. Reads return null while the
 * credential store is unavailable; writes then throw.
 */
interface SiteCredentialsStore {
    suspend fun get(sourceId: String): SiteCredentials?
    suspend fun save(sourceId: String, credentials: SiteCredentials)
    suspend fun clear(sourceId: String)

    /** Emits on collection and after every committed change, so the custom-source runtime can rebuild. */
    val changes: Flow<Unit>
}

/** Process-local [SiteCredentialsStore] for fixtures and tests. */
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
