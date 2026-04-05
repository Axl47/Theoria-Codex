package com.theoriacodex.sources.http

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultSourceHttpClientTest {
    @Test
    fun `encodes query spaces as percent-20 for get requests`() = runTest {
        var capturedRawQuery: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/probe") { exchange ->
                capturedRawQuery = exchange.requestURI.rawQuery
                val body = "{}".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { output ->
                    output.write(body)
                }
                exchange.close()
            }
            start()
        }

        try {
            val client = DefaultSourceHttpClient(maxRetries = 0)
            client.get(
                url = "http://127.0.0.1:${server.address.port}/probe",
                query = mapOf("word" to "tag one tag two"),
                headers = emptyMap(),
            )
        } finally {
            server.stop(0)
        }

        val query = capturedRawQuery.orEmpty()
        assertTrue(query.contains("word=tag%20one%20tag%20two"))
        assertFalse(query.contains("+"))
    }

    @Test
    fun `iwara 429 responses are not retried by default policy`() {
        assertFalse(defaultShouldRetryStatus("https://api.iwara.tv/search?type=videos", 429))
        assertFalse(defaultShouldRetryStatus("https://filesq.iwara.tv/file/abc", 429))
        assertFalse(defaultShouldRetryStatus("https://mikoto.iwara.tv/view?file=video.mp4", 429))
    }

    @Test
    fun `non iwara 429 responses still retry by default policy`() {
        assertTrue(defaultShouldRetryStatus("https://rule34video.com/video/123", 429))
    }

    @Test
    fun `server errors still retry by default policy`() {
        assertTrue(defaultShouldRetryStatus("https://api.iwara.tv/search?type=videos", 503))
        assertTrue(defaultShouldRetryStatus("https://rule34video.com/video/123", 500))
    }

    @Test
    fun `retry delay honors retry after header`() {
        val response = SourceHttpResponse(
            statusCode = 429,
            body = "",
            headers = mapOf("Retry-After" to listOf("12")),
        )

        assertEquals(12_000L, resolveRetryDelayMs(response, attempt = 2, retryBaseDelayMs = 300L))
    }

    @Test
    fun `retry delay falls back to exponential base when retry after missing`() {
        val response = SourceHttpResponse(statusCode = 503, body = "")

        assertEquals(600L, resolveRetryDelayMs(response, attempt = 2, retryBaseDelayMs = 300L))
    }
}
