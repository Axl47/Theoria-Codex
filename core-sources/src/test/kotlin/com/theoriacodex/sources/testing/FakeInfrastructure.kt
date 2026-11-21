package com.theoriacodex.sources.testing

import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.credentials.PixivAuthTokens
import com.theoriacodex.sources.credentials.PixivCredentialSession
import com.theoriacodex.sources.credentials.SourceCredentialsProvider
import com.theoriacodex.sources.credentials.UserApiKey
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.http.SourceHttpResponse

class FakeHttpClient : SourceHttpClient {
    var nextGetResponse: SourceHttpResponse = SourceHttpResponse(200, "{}")
    var nextPostResponse: SourceHttpResponse = SourceHttpResponse(200, "{}")

    var lastGet: RecordedRequest? = null
    var lastPost: RecordedPost? = null

    override suspend fun get(
        url: String,
        query: Map<String, String>,
        headers: Map<String, String>,
    ): SourceHttpResponse {
        lastGet = RecordedRequest(url = url, query = query, headers = headers)
        return nextGetResponse
    }

    override suspend fun postForm(
        url: String,
        form: Map<String, String>,
        headers: Map<String, String>,
    ): SourceHttpResponse {
        lastPost = RecordedPost(url = url, form = form, headers = headers)
        return nextPostResponse
    }

    override suspend fun postJson(
        url: String,
        body: String,
        headers: Map<String, String>,
    ): SourceHttpResponse {
        lastPost = RecordedPost(url = url, form = emptyMap(), headers = headers, body = body)
        return nextPostResponse
    }
}

data class RecordedRequest(
    val url: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
)

data class RecordedPost(
    val url: String,
    val form: Map<String, String>,
    val headers: Map<String, String>,
    val body: String? = null,
)

class FakeCredentialsProvider : SourceCredentialsProvider {
    var pixivTokens: PixivAuthTokens? = null
    var gelbooruCredentials: UserApiKey? = null
    var rule34XxxCredentials: UserApiKey? = null

    override suspend fun getPixivTokens(): PixivAuthTokens? = pixivTokens
    override suspend fun savePixivTokens(tokens: PixivAuthTokens) {
        pixivTokens = tokens
    }

    override suspend fun commitPixivRefresh(session: PixivCredentialSession, tokens: PixivAuthTokens?): Boolean {
        if (pixivTokens != session.tokens) return false
        pixivTokens = tokens
        return true
    }

    override suspend fun clearPixivTokens() {
        pixivTokens = null
    }

    override suspend fun userApiKey(source: SourceKey): UserApiKey? = when (source) {
        SourceKey.GELBOORU -> gelbooruCredentials
        SourceKey.RULE34XXX -> rule34XxxCredentials
        else -> null
    }

    override suspend fun saveUserApiKey(source: SourceKey, key: UserApiKey) = setUserApiKey(source, key)

    override suspend fun clearUserApiKey(source: SourceKey) = setUserApiKey(source, null)

    private fun setUserApiKey(source: SourceKey, key: UserApiKey?) {
        when (source) {
            SourceKey.GELBOORU -> gelbooruCredentials = key
            SourceKey.RULE34XXX -> rule34XxxCredentials = key
            else -> error("$source takes no API key")
        }
    }
}
