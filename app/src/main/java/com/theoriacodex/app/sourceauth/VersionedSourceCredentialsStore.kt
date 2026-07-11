package com.theoriacodex.app.sourceauth

import com.theoriacodex.sources.credentials.GelbooruCredentials
import com.theoriacodex.sources.credentials.PixivAuthTokens
import com.theoriacodex.sources.credentials.Rule34XxxCredentials
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The non-secret status exposed to the app shell.
 *
 * Storage failures are intentionally split by the action a user or caller can take. None of
 * these states contains exception text because provider credentials can occur in nested causes.
 */
sealed interface CredentialStoreRecoveryState {
    data object Loading : CredentialStoreRecoveryState

    data object Migrating : CredentialStoreRecoveryState

    data object Ready : CredentialStoreRecoveryState

    data object TemporarilyUnavailable : CredentialStoreRecoveryState

    data object ReconnectRequired : CredentialStoreRecoveryState

    data class UnsupportedVersion(val version: Int) : CredentialStoreRecoveryState
}

internal class CredentialStoreUnavailableException : IllegalStateException(
    "Source credentials are unavailable in the current recovery state",
)

/**
 * A deliberately redacted, whole-store snapshot. Equality exists for migration verification;
 * [toString] never exposes tokens, user IDs, or API keys.
 */
internal class SourceCredentialSnapshot(
    val pixivAccessToken: String? = null,
    val pixivRefreshToken: String? = null,
    val pixivExpiresAtEpochMs: Long? = null,
    val gelbooruUserId: String? = null,
    val gelbooruApiKey: String? = null,
    val rule34XxxUserId: String? = null,
    val rule34XxxApiKey: String? = null,
) {
    fun pixivTokens(): PixivAuthTokens? {
        val accessToken = pixivAccessToken?.takeIf(String::isNotBlank) ?: return null
        val refreshToken = pixivRefreshToken?.takeIf(String::isNotBlank) ?: return null
        val expiresAt = pixivExpiresAtEpochMs?.takeIf { value -> value > 0L } ?: return null
        return PixivAuthTokens(accessToken, refreshToken, expiresAt)
    }

    fun gelbooruCredentials(): GelbooruCredentials? {
        val userId = gelbooruUserId?.takeIf(String::isNotBlank) ?: return null
        val apiKey = gelbooruApiKey?.takeIf(String::isNotBlank) ?: return null
        return GelbooruCredentials(userId, apiKey)
    }

    fun rule34XxxCredentials(): Rule34XxxCredentials? {
        val userId = rule34XxxUserId?.takeIf(String::isNotBlank) ?: return null
        val apiKey = rule34XxxApiKey?.takeIf(String::isNotBlank) ?: return null
        return Rule34XxxCredentials(userId, apiKey)
    }

    fun withPixiv(tokens: PixivAuthTokens?): SourceCredentialSnapshot = SourceCredentialSnapshot(
        pixivAccessToken = tokens?.accessToken,
        pixivRefreshToken = tokens?.refreshToken,
        pixivExpiresAtEpochMs = tokens?.expiresAtEpochMs,
        gelbooruUserId = gelbooruUserId,
        gelbooruApiKey = gelbooruApiKey,
        rule34XxxUserId = rule34XxxUserId,
        rule34XxxApiKey = rule34XxxApiKey,
    )

    fun withGelbooru(credentials: GelbooruCredentials?): SourceCredentialSnapshot = SourceCredentialSnapshot(
        pixivAccessToken = pixivAccessToken,
        pixivRefreshToken = pixivRefreshToken,
        pixivExpiresAtEpochMs = pixivExpiresAtEpochMs,
        gelbooruUserId = credentials?.userId,
        gelbooruApiKey = credentials?.apiKey,
        rule34XxxUserId = rule34XxxUserId,
        rule34XxxApiKey = rule34XxxApiKey,
    )

    fun withRule34Xxx(credentials: Rule34XxxCredentials?): SourceCredentialSnapshot = SourceCredentialSnapshot(
        pixivAccessToken = pixivAccessToken,
        pixivRefreshToken = pixivRefreshToken,
        pixivExpiresAtEpochMs = pixivExpiresAtEpochMs,
        gelbooruUserId = gelbooruUserId,
        gelbooruApiKey = gelbooruApiKey,
        rule34XxxUserId = credentials?.userId,
        rule34XxxApiKey = credentials?.apiKey,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SourceCredentialSnapshot) return false
        return pixivAccessToken == other.pixivAccessToken &&
            pixivRefreshToken == other.pixivRefreshToken &&
            pixivExpiresAtEpochMs == other.pixivExpiresAtEpochMs &&
            gelbooruUserId == other.gelbooruUserId &&
            gelbooruApiKey == other.gelbooruApiKey &&
            rule34XxxUserId == other.rule34XxxUserId &&
            rule34XxxApiKey == other.rule34XxxApiKey
    }

    override fun hashCode(): Int {
        var result = pixivAccessToken.hashCode()
        result = 31 * result + pixivRefreshToken.hashCode()
        result = 31 * result + pixivExpiresAtEpochMs.hashCode()
        result = 31 * result + gelbooruUserId.hashCode()
        result = 31 * result + gelbooruApiKey.hashCode()
        result = 31 * result + rule34XxxUserId.hashCode()
        return 31 * result + rule34XxxApiKey.hashCode()
    }

    override fun toString(): String = "SourceCredentialSnapshot([REDACTED])"
}

internal sealed interface CredentialEnvelopeReadResult {
    data object Missing : CredentialEnvelopeReadResult

    data class Success(val snapshot: SourceCredentialSnapshot) : CredentialEnvelopeReadResult

    data object TemporarilyUnavailable : CredentialEnvelopeReadResult

    data object ReconnectRequired : CredentialEnvelopeReadResult

    data class UnsupportedVersion(val version: Int) : CredentialEnvelopeReadResult
}

internal sealed interface CredentialStoreWriteResult {
    data object Success : CredentialStoreWriteResult

    data object TemporarilyUnavailable : CredentialStoreWriteResult

    data object ReconnectRequired : CredentialStoreWriteResult
}

internal interface CredentialEnvelopeStore {
    suspend fun read(): CredentialEnvelopeReadResult

    /** Writes atomically and verifies a decrypting read-back before reporting success. */
    suspend fun writeVerified(snapshot: SourceCredentialSnapshot): CredentialStoreWriteResult

    suspend fun reset(): Boolean
}

internal sealed interface LegacyCredentialReadResult {
    data object Missing : LegacyCredentialReadResult

    data class Success(val snapshot: SourceCredentialSnapshot) : LegacyCredentialReadResult

    data object TemporarilyUnavailable : LegacyCredentialReadResult

    data object ReconnectRequired : LegacyCredentialReadResult
}

internal interface LegacyCredentialStore {
    suspend fun read(): LegacyCredentialReadResult

    /** Removes legacy values and their key only after the versioned envelope is authoritative. */
    suspend fun cleanup(): Boolean

    suspend fun reset(): Boolean
}

internal class VersionedSourceCredentialsStore(
    private val envelopeStore: CredentialEnvelopeStore,
    private val legacyStore: LegacyCredentialStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RecoverableSourceCredentialsStore {
    private val mutex = Mutex()
    private val mutableRecoveryState = MutableStateFlow<CredentialStoreRecoveryState>(
        CredentialStoreRecoveryState.Loading,
    )
    private var loadedSnapshot: SourceCredentialSnapshot? = null
    private var legacyCleanupPending = false

    override val recoveryState: StateFlow<CredentialStoreRecoveryState> =
        mutableRecoveryState.asStateFlow()

    override suspend fun getPixivTokens(): PixivAuthTokens? = readSnapshot()?.pixivTokens()

    override suspend fun savePixivTokens(tokens: PixivAuthTokens) {
        updateSnapshot { snapshot -> snapshot.withPixiv(tokens) }
    }

    override suspend fun clearPixivTokens() {
        updateSnapshot { snapshot -> snapshot.withPixiv(null) }
    }

    override suspend fun getGelbooruCredentials(): GelbooruCredentials? =
        readSnapshot()?.gelbooruCredentials()

    override suspend fun saveGelbooruCredentials(credentials: GelbooruCredentials) {
        updateSnapshot { snapshot -> snapshot.withGelbooru(credentials) }
    }

    override suspend fun clearGelbooruCredentials() {
        updateSnapshot { snapshot -> snapshot.withGelbooru(null) }
    }

    override suspend fun getRule34XxxCredentials(): Rule34XxxCredentials? =
        readSnapshot()?.rule34XxxCredentials()

    override suspend fun saveRule34XxxCredentials(credentials: Rule34XxxCredentials) {
        updateSnapshot { snapshot -> snapshot.withRule34Xxx(credentials) }
    }

    override suspend fun clearRule34XxxCredentials() {
        updateSnapshot { snapshot -> snapshot.withRule34Xxx(null) }
    }

    override suspend fun resetAfterReconnectRequired(): Boolean = withContext(ioDispatcher) {
        mutex.withLock {
            if (mutableRecoveryState.value != CredentialStoreRecoveryState.ReconnectRequired) {
                return@withLock false
            }
            // A reset may partially succeed or be cancelled between stores. Never keep serving a
            // cached snapshot after the user has authorized destructive recovery.
            loadedSnapshot = null
            legacyCleanupPending = false
            val envelopeReset = envelopeStore.reset()
            val legacyReset = legacyStore.reset()
            if (envelopeReset && legacyReset) {
                loadedSnapshot = SourceCredentialSnapshot()
                legacyCleanupPending = false
                mutableRecoveryState.value = CredentialStoreRecoveryState.Ready
                true
            } else {
                mutableRecoveryState.value = CredentialStoreRecoveryState.ReconnectRequired
                false
            }
        }
    }

    private suspend fun readSnapshot(): SourceCredentialSnapshot? = withContext(ioDispatcher) {
        mutex.withLock { ensureLoadedLocked() }
    }

    private suspend fun updateSnapshot(
        transform: (SourceCredentialSnapshot) -> SourceCredentialSnapshot,
    ) = withContext(ioDispatcher) {
        mutex.withLock {
            val current = ensureLoadedLocked() ?: throw CredentialStoreUnavailableException()
            val updated = transform(current)
            if (updated == current) return@withLock
            when (envelopeStore.writeVerified(updated)) {
                CredentialStoreWriteResult.Success -> {
                    loadedSnapshot = updated
                    mutableRecoveryState.value = if (legacyCleanupPending) {
                        CredentialStoreRecoveryState.Migrating
                    } else {
                        CredentialStoreRecoveryState.Ready
                    }
                }
                CredentialStoreWriteResult.TemporarilyUnavailable -> {
                    loadedSnapshot = null
                    mutableRecoveryState.value = CredentialStoreRecoveryState.TemporarilyUnavailable
                    throw CredentialStoreUnavailableException()
                }
                CredentialStoreWriteResult.ReconnectRequired -> {
                    loadedSnapshot = null
                    mutableRecoveryState.value = CredentialStoreRecoveryState.ReconnectRequired
                    throw CredentialStoreUnavailableException()
                }
            }
        }
    }

    private suspend fun ensureLoadedLocked(): SourceCredentialSnapshot? {
        loadedSnapshot?.let { current ->
            if (legacyCleanupPending) {
                legacyCleanupPending = !legacyStore.cleanup()
                mutableRecoveryState.value = if (legacyCleanupPending) {
                    CredentialStoreRecoveryState.Migrating
                } else {
                    CredentialStoreRecoveryState.Ready
                }
            }
            return current
        }

        return when (val envelope = envelopeStore.read()) {
            is CredentialEnvelopeReadResult.Success -> {
                loadedSnapshot = envelope.snapshot
                // A committed envelope always wins. Cleanup can safely resume after a process stop.
                legacyCleanupPending = true
                mutableRecoveryState.value = CredentialStoreRecoveryState.Migrating
                legacyCleanupPending = !legacyStore.cleanup()
                mutableRecoveryState.value = if (legacyCleanupPending) {
                    CredentialStoreRecoveryState.Migrating
                } else {
                    CredentialStoreRecoveryState.Ready
                }
                envelope.snapshot
            }
            CredentialEnvelopeReadResult.Missing -> migrateLegacyLocked()
            CredentialEnvelopeReadResult.TemporarilyUnavailable -> unavailableTemporarily()
            CredentialEnvelopeReadResult.ReconnectRequired -> reconnectRequired()
            is CredentialEnvelopeReadResult.UnsupportedVersion -> {
                mutableRecoveryState.value = CredentialStoreRecoveryState.UnsupportedVersion(envelope.version)
                null
            }
        }
    }

    private suspend fun migrateLegacyLocked(): SourceCredentialSnapshot? {
        return when (val legacy = legacyStore.read()) {
            LegacyCredentialReadResult.Missing -> {
                SourceCredentialSnapshot().also { snapshot ->
                    loadedSnapshot = snapshot
                    mutableRecoveryState.value = CredentialStoreRecoveryState.Ready
                }
            }
            is LegacyCredentialReadResult.Success -> {
                mutableRecoveryState.value = CredentialStoreRecoveryState.Migrating
                when (envelopeStore.writeVerified(legacy.snapshot)) {
                    CredentialStoreWriteResult.Success -> {
                        loadedSnapshot = legacy.snapshot
                        legacyCleanupPending = !legacyStore.cleanup()
                        mutableRecoveryState.value = if (legacyCleanupPending) {
                            CredentialStoreRecoveryState.Migrating
                        } else {
                            CredentialStoreRecoveryState.Ready
                        }
                        legacy.snapshot
                    }
                    CredentialStoreWriteResult.TemporarilyUnavailable -> unavailableTemporarily()
                    CredentialStoreWriteResult.ReconnectRequired -> reconnectRequired()
                }
            }
            LegacyCredentialReadResult.TemporarilyUnavailable -> unavailableTemporarily()
            LegacyCredentialReadResult.ReconnectRequired -> reconnectRequired()
        }
    }

    private fun unavailableTemporarily(): SourceCredentialSnapshot? {
        mutableRecoveryState.value = CredentialStoreRecoveryState.TemporarilyUnavailable
        return null
    }

    private fun reconnectRequired(): SourceCredentialSnapshot? {
        mutableRecoveryState.value = CredentialStoreRecoveryState.ReconnectRequired
        return null
    }
}
