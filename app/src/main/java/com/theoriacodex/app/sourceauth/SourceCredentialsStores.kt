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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

sealed interface CredentialStoreRecoveryState {
    data object Ready : CredentialStoreRecoveryState

    data object ReconnectRequired : CredentialStoreRecoveryState
}

class AndroidSecureSourceCredentialsStore(
    context: Context,
) : SourceCredentialsProvider {
    private val appContext = context.applicationContext
    private val preferences = RecoverableCredentialPreferences(
        openPreferences = {
            AndroidCredentialPreferences(createEncryptedPrefs(appContext))
        },
        resetStorage = {
            appContext.deleteSharedPreferences(PREFS_NAME)
            deleteMasterKey()
        },
    )

    val recoveryState: StateFlow<CredentialStoreRecoveryState> = preferences.recoveryState

    override suspend fun getPixivTokens(): PixivAuthTokens? = withContext(Dispatchers.IO) {
        val stored = preferences.read {
            StoredPixivCredentials(
                accessToken = getString(KEY_PIXIV_ACCESS_TOKEN),
                refreshToken = getString(KEY_PIXIV_REFRESH_TOKEN),
                expiresAtEpochMs = getLong(KEY_PIXIV_EXPIRES_AT, -1L),
            )
        } ?: return@withContext null
        val accessToken = stored.accessToken
        val refreshToken = stored.refreshToken
        val expiresAt = stored.expiresAtEpochMs.takeIf { it > 0L }
        if (accessToken.isNullOrBlank() || refreshToken.isNullOrBlank() || expiresAt == null) {
            null
        } else {
            PixivAuthTokens(
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresAtEpochMs = expiresAt,
            )
        }
    }

    override suspend fun savePixivTokens(tokens: PixivAuthTokens) = withContext(Dispatchers.IO) {
        preferences.update {
            putString(KEY_PIXIV_ACCESS_TOKEN, tokens.accessToken)
            putString(KEY_PIXIV_REFRESH_TOKEN, tokens.refreshToken)
            putLong(KEY_PIXIV_EXPIRES_AT, tokens.expiresAtEpochMs)
        }
        Unit
    }

    override suspend fun clearPixivTokens() = withContext(Dispatchers.IO) {
        preferences.update {
            remove(KEY_PIXIV_ACCESS_TOKEN)
            remove(KEY_PIXIV_REFRESH_TOKEN)
            remove(KEY_PIXIV_EXPIRES_AT)
        }
        Unit
    }

    override suspend fun getGelbooruCredentials(): GelbooruCredentials? = withContext(Dispatchers.IO) {
        val stored = preferences.read {
            StoredApiCredentials(
                userId = getString(KEY_GELBOORU_USER_ID),
                apiKey = getString(KEY_GELBOORU_API_KEY),
            )
        } ?: return@withContext null
        val userId = stored.userId
        val apiKey = stored.apiKey
        if (userId.isNullOrBlank() || apiKey.isNullOrBlank()) {
            null
        } else {
            GelbooruCredentials(userId = userId, apiKey = apiKey)
        }
    }

    override suspend fun saveGelbooruCredentials(credentials: GelbooruCredentials) = withContext(Dispatchers.IO) {
        preferences.update {
            putString(KEY_GELBOORU_USER_ID, credentials.userId)
            putString(KEY_GELBOORU_API_KEY, credentials.apiKey)
        }
        Unit
    }

    override suspend fun clearGelbooruCredentials() = withContext(Dispatchers.IO) {
        preferences.update {
            remove(KEY_GELBOORU_USER_ID)
            remove(KEY_GELBOORU_API_KEY)
        }
        Unit
    }

    override suspend fun getRule34XxxCredentials(): Rule34XxxCredentials? = withContext(Dispatchers.IO) {
        val stored = preferences.read {
            StoredApiCredentials(
                userId = getString(KEY_RULE34XXX_USER_ID),
                apiKey = getString(KEY_RULE34XXX_API_KEY),
            )
        } ?: return@withContext null
        val userId = stored.userId
        val apiKey = stored.apiKey
        if (userId.isNullOrBlank() || apiKey.isNullOrBlank()) {
            null
        } else {
            Rule34XxxCredentials(userId = userId, apiKey = apiKey)
        }
    }

    override suspend fun saveRule34XxxCredentials(credentials: Rule34XxxCredentials) = withContext(Dispatchers.IO) {
        preferences.update {
            putString(KEY_RULE34XXX_USER_ID, credentials.userId)
            putString(KEY_RULE34XXX_API_KEY, credentials.apiKey)
        }
        Unit
    }

    override suspend fun clearRule34XxxCredentials() = withContext(Dispatchers.IO) {
        preferences.update {
            remove(KEY_RULE34XXX_USER_ID)
            remove(KEY_RULE34XXX_API_KEY)
        }
        Unit
    }

    /**
     * Clears an unreadable encrypted store only after the user has chosen to reconnect sources.
     * Ordinary startup and reads never delete credential material automatically.
     */
    suspend fun resetAfterReconnectRequired(): Boolean = withContext(Dispatchers.IO) {
        preferences.resetAfterReconnectRequired()
    }
}

internal interface CredentialPreferences {
    fun getString(key: String): String?

    fun getLong(key: String, defaultValue: Long): Long

    fun update(block: CredentialPreferencesEditor.() -> Unit)
}

internal interface CredentialPreferencesEditor {
    fun putString(key: String, value: String)

    fun putLong(key: String, value: Long)

    fun remove(key: String)
}

private class AndroidCredentialPreferences(
    private val preferences: SharedPreferences,
) : CredentialPreferences {
    override fun getString(key: String): String? = preferences.getString(key, null)

    override fun getLong(key: String, defaultValue: Long): Long {
        return preferences.getLong(key, defaultValue)
    }

    override fun update(block: CredentialPreferencesEditor.() -> Unit) {
        val editor = preferences.edit()
        AndroidCredentialPreferencesEditor(editor).block()
        editor.apply()
    }
}

private class AndroidCredentialPreferencesEditor(
    private val editor: SharedPreferences.Editor,
) : CredentialPreferencesEditor {
    override fun putString(key: String, value: String) {
        editor.putString(key, value)
    }

    override fun putLong(key: String, value: Long) {
        editor.putLong(key, value)
    }

    override fun remove(key: String) {
        editor.remove(key)
    }
}

internal class RecoverableCredentialPreferences(
    private val openPreferences: () -> CredentialPreferences,
    private val resetStorage: () -> Unit,
) {
    private val lock = Any()
    private val mutableRecoveryState = MutableStateFlow<CredentialStoreRecoveryState>(
        CredentialStoreRecoveryState.Ready,
    )
    private var activePreferences: CredentialPreferences? = openOrMarkReconnectRequired()

    val recoveryState: StateFlow<CredentialStoreRecoveryState> = mutableRecoveryState.asStateFlow()

    fun <T> read(block: CredentialPreferences.() -> T): T? = synchronized(lock) {
        val current = activePreferences ?: return@synchronized null
        try {
            current.block()
        } catch (error: Exception) {
            if (!error.isRecoverableEncryptedPrefsFailure()) throw error
            markReconnectRequired()
            null
        }
    }

    fun update(block: CredentialPreferencesEditor.() -> Unit): Boolean = synchronized(lock) {
        val current = activePreferences ?: return@synchronized false
        try {
            current.update(block)
            true
        } catch (error: Exception) {
            if (!error.isRecoverableEncryptedPrefsFailure()) throw error
            markReconnectRequired()
            false
        }
    }

    fun resetAfterReconnectRequired(): Boolean = synchronized(lock) {
        if (mutableRecoveryState.value != CredentialStoreRecoveryState.ReconnectRequired) {
            return@synchronized true
        }
        resetStorage()
        activePreferences = openOrMarkReconnectRequired()
        activePreferences != null
    }

    private fun openOrMarkReconnectRequired(): CredentialPreferences? {
        return try {
            openPreferences().also {
                mutableRecoveryState.value = CredentialStoreRecoveryState.Ready
            }
        } catch (error: Exception) {
            if (!error.isRecoverableEncryptedPrefsFailure()) throw error
            markReconnectRequired()
            null
        }
    }

    private fun markReconnectRequired() {
        activePreferences = null
        mutableRecoveryState.value = CredentialStoreRecoveryState.ReconnectRequired
    }
}

private fun createEncryptedPrefs(context: Context): SharedPreferences {
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

private data class StoredPixivCredentials(
    val accessToken: String?,
    val refreshToken: String?,
    val expiresAtEpochMs: Long,
)

private data class StoredApiCredentials(
    val userId: String?,
    val apiKey: String?,
)

private fun deleteMasterKey() {
    runCatching {
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            if (containsAlias(MasterKey.DEFAULT_MASTER_KEY_ALIAS)) {
                deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            }
        }
    }
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

private const val PREFS_NAME = "theoria_source_credentials"
private const val KEY_PIXIV_ACCESS_TOKEN = "pixiv_access_token"
private const val KEY_PIXIV_REFRESH_TOKEN = "pixiv_refresh_token"
private const val KEY_PIXIV_EXPIRES_AT = "pixiv_expires_at"
private const val KEY_GELBOORU_USER_ID = "gelbooru_user_id"
private const val KEY_GELBOORU_API_KEY = "gelbooru_api_key"
private const val KEY_RULE34XXX_USER_ID = "rule34xxx_user_id"
private const val KEY_RULE34XXX_API_KEY = "rule34xxx_api_key"
