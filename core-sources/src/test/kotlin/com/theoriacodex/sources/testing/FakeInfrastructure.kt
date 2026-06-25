package com.theoriacodex.sources.testing

import com.theoriacodex.sources.credentials.GelbooruCredentials
import com.theoriacodex.sources.credentials.PixivAuthTokens
import com.theoriacodex.sources.credentials.Rule34XxxCredentials
import com.theoriacodex.sources.credentials.SourceCredentialsProvider
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
    var gelbooruCredentials: GelbooruCredentials? = null
    var rule34XxxCredentials: Rule34XxxCredentials? = null

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
