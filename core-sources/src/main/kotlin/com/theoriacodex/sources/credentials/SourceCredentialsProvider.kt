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

interface SourceCredentialsProvider {
    suspend fun getPixivTokens(): PixivAuthTokens?
    suspend fun savePixivTokens(tokens: PixivAuthTokens)
    suspend fun clearPixivTokens()

    suspend fun getGelbooruCredentials(): GelbooruCredentials?
    suspend fun saveGelbooruCredentials(credentials: GelbooruCredentials)
    suspend fun clearGelbooruCredentials()
}
