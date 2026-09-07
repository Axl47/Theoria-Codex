package com.theoriacodex.sources.testing

import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.http.SourceHttpResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** An offline protocol transcript: unexpected, reordered, or repeated requests fail immediately. */
class ScriptedHttpClient : SourceHttpClient {
    private val pending = ArrayDeque<ExpectedGet>()

    fun enqueueGet(
        url: String,
        query: Map<String, String> = emptyMap(),
        body: String = "",
        statusCode: Int = 200,
        requiredHeaders: Map<String, String> = emptyMap(),
        failure: Throwable? = null,
    ) {
        pending += ExpectedGet(url, query, requiredHeaders, SourceHttpResponse(statusCode, body), failure)
    }

    override suspend fun get(url: String, query: Map<String, String>, headers: Map<String, String>): SourceHttpResponse {
        val expected = pending.removeFirstOrNull() ?: error("Unexpected GET $url $query")
        assertEquals("GET endpoint", expected.url, url)
        assertEquals("GET query for $url", expected.query, query)
        expected.headers.forEach { (name, value) -> assertEquals("Header $name for $url", value, headers[name]) }
        expected.failure?.let { throw it }
        return expected.response
    }

    override suspend fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): SourceHttpResponse =
        error("Unexpected POST $url")

    fun assertExhausted() = assertTrue("Unconsumed requests: ${pending.map { it.url }}", pending.isEmpty())

    private data class ExpectedGet(
        val url: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val response: SourceHttpResponse,
        val failure: Throwable?,
    )
}
