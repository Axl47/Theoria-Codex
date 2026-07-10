package com.theoriacodex.sources.http

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultSourceHttpClientTest {
    @Test
    fun `returns exact binary range response and headers`() = runTest {
        var capturedRange: String? = null
        var capturedEncoding: String? = null
        val expectedBody = byteArrayOf(0, 1, 0xFF.toByte(), 2)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/range") { exchange ->
                capturedRange = exchange.requestHeaders.getFirst("Range")
                capturedEncoding = exchange.requestHeaders.getFirst("Accept-Encoding")
                exchange.responseHeaders.add("Content-Range", "bytes 4-7/12")
                exchange.sendResponseHeaders(206, expectedBody.size.toLong())
                exchange.responseBody.use { output ->
                    output.write(expectedBody)
                }
                exchange.close()
            }
            start()
        }

        val response = try {
            DefaultSourceHttpClient(maxRetries = 0).getBytes(
                url = "http://127.0.0.1:${server.address.port}/range",
                range = SourceByteRange(startInclusive = 4, endInclusive = 7),
                maxBodyBytes = expectedBody.size,
            )
        } finally {
            server.stop(0)
        }

        assertEquals("bytes=4-7", capturedRange)
        assertEquals("identity", capturedEncoding)
        assertEquals(206, response.statusCode)
        assertArrayEquals(expectedBody, response.body)
        assertEquals(
            "bytes 4-7/12",
            response.headers.entries
                .first { (name, _) -> name.equals("Content-Range", ignoreCase = true) }
                .value
                .single(),
        )
    }

    @Test
    fun `accepts full 200 response when server ignores byte range`() = runTest {
        val expectedBody = byteArrayOf(9, 8, 7, 6)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/full") { exchange ->
                exchange.sendResponseHeaders(200, expectedBody.size.toLong())
                exchange.responseBody.use { output ->
                    output.write(expectedBody)
                }
                exchange.close()
            }
            start()
        }

        val response = try {
            DefaultSourceHttpClient(maxRetries = 0).getBytes(
                url = "http://127.0.0.1:${server.address.port}/full",
                range = SourceByteRange(startInclusive = 0, endInclusive = 3),
            )
        } finally {
            server.stop(0)
        }

        assertEquals(200, response.statusCode)
        assertArrayEquals(expectedBody, response.body)
    }

    @Test
    fun `rejects binary response beyond caller size limit`() = runTest {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/oversized") { exchange ->
                val body = byteArrayOf(1, 2, 3, 4, 5)
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { output ->
                    output.write(body)
                }
                exchange.close()
            }
            start()
        }

        val failure = try {
            runCatching {
                DefaultSourceHttpClient(maxRetries = 0).getBytes(
                    url = "http://127.0.0.1:${server.address.port}/oversized",
                    maxBodyBytes = 4,
                )
            }.exceptionOrNull()
        } finally {
            server.stop(0)
        }

        assertTrue(failure is SourceHttpBodyTooLargeException)
        assertEquals(4, (failure as SourceHttpBodyTooLargeException).maxBodyBytes)
    }

    @Test
    fun `cancellation is propagated while waiting to retry binary request`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/retry") { exchange ->
                exchange.sendResponseHeaders(503, -1)
                exchange.close()
            }
            start()
        }

        val failure = try {
            runCatching {
                withTimeout(100L) {
                    DefaultSourceHttpClient(
                        maxRetries = 2,
                        retryBaseDelayMs = 60_000L,
                    ).getBytes("http://127.0.0.1:${server.address.port}/retry")
                }
            }.exceptionOrNull()
        } finally {
            server.stop(0)
        }

        assertTrue(failure is TimeoutCancellationException)
    }

    @Test
    fun `cancellation disconnects an active binary response read`() = runBlocking {
        val releaseServer = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/stalled") { exchange ->
                exchange.sendResponseHeaders(200, 10L)
                exchange.responseBody.write(byteArrayOf(1))
                exchange.responseBody.flush()
                releaseServer.await(2L, TimeUnit.SECONDS)
                exchange.close()
            }
            start()
        }

        val (failure, cancellationElapsedMs) = try {
            val startedAt = System.nanoTime()
            val result = runCatching {
                withTimeout(100L) {
                    DefaultSourceHttpClient(
                        readTimeoutMs = 10_000,
                        maxRetries = 0,
                    ).getBytes("http://127.0.0.1:${server.address.port}/stalled")
                }
            }
            result.exceptionOrNull() to
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        } finally {
            releaseServer.countDown()
            server.stop(0)
        }

        assertTrue(failure is TimeoutCancellationException)
        assertTrue("Cancellation took ${cancellationElapsedMs}ms", cancellationElapsedMs < 1_000L)
    }

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
