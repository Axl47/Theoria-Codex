package com.theoriacodex.sources.http

data class SourceHttpResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, List<String>> = emptyMap(),
)

interface SourceHttpClient {
    suspend fun get(
        url: String,
        query: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): SourceHttpResponse

    suspend fun postForm(
        url: String,
        form: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
    ): SourceHttpResponse

    suspend fun postJson(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ): SourceHttpResponse {
        throw UnsupportedOperationException("JSON POST is not supported by this HTTP client")
    }
}
