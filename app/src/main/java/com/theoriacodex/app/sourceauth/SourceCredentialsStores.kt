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
import kotlinx.coroutines.withContext

class AndroidSecureSourceCredentialsStore(
    context: Context,
) : SourceCredentialsProvider {
    private val appContext = context.applicationContext
    private val prefs = createRecoveringEncryptedPrefs(appContext)

    override suspend fun getPixivTokens(): PixivAuthTokens? = withContext(Dispatchers.IO) {
        val accessToken = prefs.getString(KEY_PIXIV_ACCESS_TOKEN, null)
        val refreshToken = prefs.getString(KEY_PIXIV_REFRESH_TOKEN, null)
        val expiresAt = prefs.getLong(KEY_PIXIV_EXPIRES_AT, -1L).takeIf { it > 0L }
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
        prefs.edit()
            .putString(KEY_PIXIV_ACCESS_TOKEN, tokens.accessToken)
            .putString(KEY_PIXIV_REFRESH_TOKEN, tokens.refreshToken)
            .putLong(KEY_PIXIV_EXPIRES_AT, tokens.expiresAtEpochMs)
            .apply()
    }

    override suspend fun clearPixivTokens() = withContext(Dispatchers.IO) {
        prefs.edit()
            .remove(KEY_PIXIV_ACCESS_TOKEN)
            .remove(KEY_PIXIV_REFRESH_TOKEN)
            .remove(KEY_PIXIV_EXPIRES_AT)
            .apply()
    }

    override suspend fun getGelbooruCredentials(): GelbooruCredentials? = withContext(Dispatchers.IO) {
        val userId = prefs.getString(KEY_GELBOORU_USER_ID, null)
        val apiKey = prefs.getString(KEY_GELBOORU_API_KEY, null)
        if (userId.isNullOrBlank() || apiKey.isNullOrBlank()) {
            null
        } else {
            GelbooruCredentials(userId = userId, apiKey = apiKey)
        }
    }

    override suspend fun saveGelbooruCredentials(credentials: GelbooruCredentials) = withContext(Dispatchers.IO) {
        prefs.edit()
            .putString(KEY_GELBOORU_USER_ID, credentials.userId)
            .putString(KEY_GELBOORU_API_KEY, credentials.apiKey)
            .apply()
    }

    override suspend fun clearGelbooruCredentials() = withContext(Dispatchers.IO) {
        prefs.edit()
            .remove(KEY_GELBOORU_USER_ID)
            .remove(KEY_GELBOORU_API_KEY)
            .apply()
    }

    override suspend fun getRule34XxxCredentials(): Rule34XxxCredentials? = withContext(Dispatchers.IO) {
        val userId = prefs.getString(KEY_RULE34XXX_USER_ID, null)
        val apiKey = prefs.getString(KEY_RULE34XXX_API_KEY, null)
        if (userId.isNullOrBlank() || apiKey.isNullOrBlank()) {
            null
        } else {
            Rule34XxxCredentials(userId = userId, apiKey = apiKey)
        }
    }

    override suspend fun saveRule34XxxCredentials(credentials: Rule34XxxCredentials) = withContext(Dispatchers.IO) {
        prefs.edit()
            .putString(KEY_RULE34XXX_USER_ID, credentials.userId)
            .putString(KEY_RULE34XXX_API_KEY, credentials.apiKey)
            .apply()
    }

    override suspend fun clearRule34XxxCredentials() = withContext(Dispatchers.IO) {
        prefs.edit()
            .remove(KEY_RULE34XXX_USER_ID)
            .remove(KEY_RULE34XXX_API_KEY)
            .apply()
    }
}

private fun createRecoveringEncryptedPrefs(context: Context): SharedPreferences {
    return try {
        createEncryptedPrefs(context)
    } catch (error: Exception) {
        if (!error.isRecoverableEncryptedPrefsFailure()) throw error
        context.deleteSharedPreferences(PREFS_NAME)
        deleteMasterKey()
        createEncryptedPrefs(context)
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

private fun Exception.isRecoverableEncryptedPrefsFailure(): Boolean {
    return generateSequence(this as Throwable?) { error -> error.cause }
        .any { error ->
            val name = error::class.java.name
            name == "javax.crypto.AEADBadTagException" ||
                name == "android.security.KeyStoreException" ||
                name == "com.google.crypto.tink.shaded.protobuf.InvalidProtocolBufferException"
        }
}

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
