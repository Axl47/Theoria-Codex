package com.theoriacodex.app.sourceauth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.theoriacodex.sources.credentials.GelbooruCredentials
import com.theoriacodex.sources.credentials.PixivAuthTokens
import com.theoriacodex.sources.credentials.Rule34XxxCredentials
import com.theoriacodex.sources.credentials.SourceCredentialsProvider
import java.security.KeyStore
import kotlinx.coroutines.flow.StateFlow

class AndroidSecureSourceCredentialsStore(
    context: Context,
) : RecoverableSourceCredentialsStore {
    private val appContext = context.applicationContext
    private val delegate = VersionedSourceCredentialsStore(
        envelopeStore = AndroidCredentialEnvelopeStore(appContext),
        legacyStore = AndroidLegacyCredentialStore(appContext),
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

@Suppress("DEPRECATION") // Read-only migration seam for installs created before the Keystore envelope.
internal fun createEncryptedPrefs(context: Context): SharedPreferences {
    return EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
}

internal fun Exception.isRecoverableEncryptedPrefsFailure(): Boolean {
    return generateSequence(this as Throwable?) { error -> error.cause }
        .any { error ->
            val name = error::class.java.name
            name == "javax.crypto.AEADBadTagException" ||
                name == "android.security.KeyStoreException" ||
                name == "com.google.crypto.tink.shaded.protobuf.InvalidProtocolBufferException"
        }
}

@Suppress("DEPRECATION") // Removes the retired Security Crypto alias after verified migration.
internal fun deleteLegacyMasterKey(): Boolean {
    return runCatching {
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            if (containsAlias(MasterKey.DEFAULT_MASTER_KEY_ALIAS)) {
                deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            }
        }
        true
    }.getOrDefault(false)
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

internal const val PREFS_NAME = "theoria_source_credentials"
internal const val KEY_PIXIV_ACCESS_TOKEN = "pixiv_access_token"
internal const val KEY_PIXIV_REFRESH_TOKEN = "pixiv_refresh_token"
internal const val KEY_PIXIV_EXPIRES_AT = "pixiv_expires_at"
internal const val KEY_GELBOORU_USER_ID = "gelbooru_user_id"
internal const val KEY_GELBOORU_API_KEY = "gelbooru_api_key"
internal const val KEY_RULE34XXX_USER_ID = "rule34xxx_user_id"
internal const val KEY_RULE34XXX_API_KEY = "rule34xxx_api_key"
