package com.theoriacodex.sources.credentials

import com.theoriacodex.domain.model.SourceKey

data class PixivAuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochMs: Long,
)

data class PixivCredentialSession(val tokens: PixivAuthTokens?, val revision: Long)

data class UserApiKey(val userId: String, val apiKey: String)

typealias UserApiKeyCredentialLookup = suspend () -> UserApiKey?

enum class ApiKeyUse {
    OPTIONAL,

    REQUIRED,
}

interface SourceCredentialsProvider {
    suspend fun getPixivTokens(): PixivAuthTokens?
    suspend fun savePixivTokens(tokens: PixivAuthTokens)
    suspend fun clearPixivTokens()

    suspend fun getPixivSession(): PixivCredentialSession = PixivCredentialSession(getPixivTokens(), 0L)

    suspend fun commitPixivRefresh(session: PixivCredentialSession, tokens: PixivAuthTokens?): Boolean = false

    suspend fun userApiKey(source: SourceKey): UserApiKey? = null
    suspend fun saveUserApiKey(source: SourceKey, key: UserApiKey) = Unit
    suspend fun clearUserApiKey(source: SourceKey) = Unit
}
