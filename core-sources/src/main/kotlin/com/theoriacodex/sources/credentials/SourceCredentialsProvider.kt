package com.theoriacodex.sources.credentials

data class PixivAuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochMs: Long,
)

/** Process-local authority for a refresh; credentials themselves retain their existing wire format. */
data class PixivCredentialSession(val tokens: PixivAuthTokens?, val revision: Long)

data class GelbooruCredentials(
    val userId: String,
    val apiKey: String,
)

data class Rule34XxxCredentials(
    val userId: String,
    val apiKey: String,
)

interface SourceCredentialsProvider {
    suspend fun getPixivTokens(): PixivAuthTokens?
    suspend fun savePixivTokens(tokens: PixivAuthTokens)
    suspend fun clearPixivTokens()

    suspend fun getPixivSession(): PixivCredentialSession = PixivCredentialSession(getPixivTokens(), 0L)

    /** Read-only credential providers reject refresh writes. Mutable owners must commit atomically. */
    suspend fun commitPixivRefresh(session: PixivCredentialSession, tokens: PixivAuthTokens?): Boolean = false

    suspend fun getGelbooruCredentials(): GelbooruCredentials?
    suspend fun saveGelbooruCredentials(credentials: GelbooruCredentials)
    suspend fun clearGelbooruCredentials()

    suspend fun getRule34XxxCredentials(): Rule34XxxCredentials?
    suspend fun saveRule34XxxCredentials(credentials: Rule34XxxCredentials)
    suspend fun clearRule34XxxCredentials()
}
