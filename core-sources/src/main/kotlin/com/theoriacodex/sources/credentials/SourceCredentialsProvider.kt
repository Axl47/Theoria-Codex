package com.theoriacodex.sources.credentials

data class PixivAuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochMs: Long,
)

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

    suspend fun getGelbooruCredentials(): GelbooruCredentials?
    suspend fun saveGelbooruCredentials(credentials: GelbooruCredentials)
    suspend fun clearGelbooruCredentials()

    suspend fun getRule34XxxCredentials(): Rule34XxxCredentials?
    suspend fun saveRule34XxxCredentials(credentials: Rule34XxxCredentials)
    suspend fun clearRule34XxxCredentials()
}
