package com.theoriacodex.sources.credentials

import com.theoriacodex.domain.model.SourceKey

data class PixivAuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochMs: Long,
)

/** Process-local authority for a refresh; credentials themselves retain their existing wire format. */
data class PixivCredentialSession(val tokens: PixivAuthTokens?, val revision: Long)

/** A user + API key pair: Gelbooru-family `user_id` + `api_key`, or Danbooru-family `login` + `api_key`. */
data class UserApiKey(val userId: String, val apiKey: String)

/** Reads the keys saved for one site; a site without saved keys returns null. */
typealias UserApiKeyCredentialLookup = suspend () -> UserApiKey?

/** Whether a booru site's API calls need saved keys. */
enum class ApiKeyUse {
    /** Attach the keys when the user saved them. */
    OPTIONAL,

    /** Fail with an auth error before any API call that lacks them. */
    REQUIRED,
}

interface SourceCredentialsProvider {
    suspend fun getPixivTokens(): PixivAuthTokens?
    suspend fun savePixivTokens(tokens: PixivAuthTokens)
    suspend fun clearPixivTokens()

    suspend fun getPixivSession(): PixivCredentialSession = PixivCredentialSession(getPixivTokens(), 0L)

    /** Read-only credential providers reject refresh writes. Mutable owners must commit atomically. */
    suspend fun commitPixivRefresh(session: PixivCredentialSession, tokens: PixivAuthTokens?): Boolean = false

    /**
     * The keys saved for a built-in site that takes them ([SourceKey.GELBOORU], [SourceKey.RULE34XXX]),
     * each in its own slot. The defaults describe a read-only provider that holds none.
     */
    suspend fun userApiKey(source: SourceKey): UserApiKey? = null
    suspend fun saveUserApiKey(source: SourceKey, key: UserApiKey) = Unit
    suspend fun clearUserApiKey(source: SourceKey) = Unit
}
