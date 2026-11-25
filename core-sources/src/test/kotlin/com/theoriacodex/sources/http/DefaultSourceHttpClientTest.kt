package com.theoriacodex.sources.http

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultSourceHttpClientTest {
    @Test
    fun `text responses use an eight mebibyte default ceiling`() {
        assertEquals(8 * 1024 * 1024, DEFAULT_MAX_SOURCE_TEXT_BODY_BYTES)
    }

    @Test
    fun `rejects oversized get text response with typed transport failure`() = runTest {
        val oversizedLength = DEFAULT_MAX_SOURCE_TEXT_BODY_BYTES.toLong() + 1L
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/oversized-text") { exchange ->
                exchange.sendResponseHeaders(200, oversizedLength)
                exchange.close()
            }
            start()
        }

        val failure = try {
            runCatching {
                DefaultSourceHttpClient(maxRetries = 0).get(
                    "http://127.0.0.1:${server.address.port}/oversized-text",
                )
            }.exceptionOrNull()
        } finally {
            server.stop(0)
        }

        assertTrue(failure is SourceHttpBodyTooLargeException)
        assertEquals(
            DEFAULT_MAX_SOURCE_TEXT_BODY_BYTES,
            (failure as SourceHttpBodyTooLargeException).maxBodyBytes,
        )
    }

    @Test
    fun `post form shares bounded response path and preserves request encoding`() = runTest {
        var capturedMethod: String? = null
        var capturedContentType: String? = null
        var capturedBody: String? = null
        val responseBody = "accepted"
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/form") { exchange ->
                capturedMethod = exchange.requestMethod
                capturedContentType = exchange.requestHeaders.getFirst("Content-Type")
                capturedBody = exchange.requestBody.bufferedReader().use { it.readText() }
                val bytes = responseBody.toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { output -> output.write(bytes) }
                exchange.close()
            }
            start()
        }

        val response = try {
            DefaultSourceHttpClient(maxRetries = 0).postForm(
                url = "http://127.0.0.1:${server.address.port}/form",
                form = mapOf("tag" to "blue hair"),
            )
        } finally {
            server.stop(0)
        }

        assertEquals("POST", capturedMethod)
        assertTrue(capturedContentType.orEmpty().startsWith("application/x-www-form-urlencoded"))
        assertEquals("tag=blue%20hair", capturedBody)
        assertEquals(responseBody, response.body)
    }

    @Test
    fun `blocked network access is an ordinary failure instead of a crash (ADR 0051)`() = runBlocking {
        val client = OkHttpClient.Builder()
            .dns { throw SecurityException("Permission denied (missing INTERNET permission?)") }
            .addInterceptor(unexpectedFailuresAsIoExceptions)
            .build()

        val failure = runCatching {
            client.newCall(Request.Builder().url("https://example.test/").build()).await { it.code }
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertTrue(generateSequence(failure) { it.cause }.any { it is SecurityException })
    }

    @Test
    fun `the default source client turns a blocked lookup into an ordinary failure (ADR 0051)`() {
        listOf(SecurityException("blocked"), IllegalStateException("resolver broke")).forEach { lookupFailure ->
            val blocked = defaultSourceOkHttpClient.newBuilder().dns { throw lookupFailure }.build()

            val failure = runCatching { blocked.newCall(Request.Builder().url("https://example.test/").build()).execute() }.exceptionOrNull()

            assertTrue("$failure", failure is IOException)
            assertTrue("$failure", generateSequence(failure) { it.cause }.any { it === lookupFailure })
        }
    }

    @Test
    fun `response headers are found whatever case the server used`() = runTest {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/headers") { exchange ->
                exchange.responseHeaders.add("Retry-After", "5")
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            }
            start()
        }

        val response = try {
            DefaultSourceHttpClient(maxRetries = 0).get("http://127.0.0.1:${server.address.port}/headers")
        } finally {
            server.stop(0)
        }

        assertEquals(listOf("5"), response.headers["retry-after"])
        assertEquals(listOf("5"), response.headers["Retry-After"])
    }

    @Test
    fun `post response uses the same default text ceiling`() = runTest {
        val oversizedLength = DEFAULT_MAX_SOURCE_TEXT_BODY_BYTES.toLong() + 1L
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/oversized-post") { exchange ->
                exchange.requestBody.close()
                exchange.sendResponseHeaders(200, oversizedLength)
                exchange.close()
            }
            start()
        }

        val failure = try {
            runCatching {
                DefaultSourceHttpClient(maxRetries = 0).postJson(
                    url = "http://127.0.0.1:${server.address.port}/oversized-post",
                    body = "{}",
                )
            }.exceptionOrNull()
        } finally {
            server.stop(0)
        }

        assertTrue(failure is SourceHttpBodyTooLargeException)
        assertEquals(
            DEFAULT_MAX_SOURCE_TEXT_BODY_BYTES,
            (failure as SourceHttpBodyTooLargeException).maxBodyBytes,
        )
    }

    @Test
    fun `cancellation disconnects an active post response read (ADR 0051)`() = runBlocking {
        val disconnected = CountDownLatch(1)
        val handlerStarted = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/stalled-post") { exchange ->
                exchange.requestBody.close()
                exchange.sendResponseHeaders(200, 0L)
                handlerStarted.countDown()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                try {
                    while (System.nanoTime() < deadline) {
                        exchange.responseBody.write(byteArrayOf(1))
                        exchange.responseBody.flush()
                        Thread.sleep(20L)
                    }
                } catch (_: IOException) {
                    disconnected.countDown()
                } finally {
                    runCatching { exchange.close() }
                }
            }
            start()
        }

        val call = launch(Dispatchers.IO) {
            DefaultSourceHttpClient(readTimeoutMs = 10_000, maxRetries = 0)
                .postJson(url = "http://127.0.0.1:${server.address.port}/stalled-post", body = "{}")
        }
        val disconnectedBeforeRelease = try {
            assertTrue("the request never reached the server", handlerStarted.await(5L, TimeUnit.SECONDS))
            call.cancel()
            disconnected.await(2L, TimeUnit.SECONDS)
        } finally {
            server.stop(0)
        }
        call.join()

        assertTrue(call.isCancelled)
        assertTrue("the call kept reading after its caller was cancelled", disconnectedBeforeRelease)
    }

    @Test
    fun `returns exact binary range response and headers (ADR 0051)`() = runTest {
        val capturedRanges = mutableListOf<String?>()
        val capturedEncodings = mutableListOf<String?>()
        val expectedBody = byteArrayOf(0, 1, 0xFF.toByte(), 2)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/range") { exchange ->
                capturedRanges += exchange.requestHeaders.getFirst("Range")
                capturedEncodings += exchange.requestHeaders.getFirst("Accept-Encoding")
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
            val client = DefaultSourceHttpClient(maxRetries = 0)
            val url = "http://127.0.0.1:${server.address.port}/range"
            client.getBytes(url = url, range = SourceByteRange(startInclusive = 0, endInclusive = 3), maxBodyBytes = expectedBody.size)
            client.getBytes(url = url, range = SourceByteRange(startInclusive = 4, endInclusive = 7), maxBodyBytes = expectedBody.size)
        } finally {
            server.stop(0)
        }

        assertEquals(listOf<String?>("bytes=0-3", "bytes=4-7"), capturedRanges)
        assertEquals(listOf<String?>("identity", "identity"), capturedEncodings)
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
    fun `encodes query spaces as percent-20 for get requests (ADR 0051)`() = runTest {
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
    fun `a redirect may change host but never scheme (ADR 0051)`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            val port = address.port
            createContext("/cross-host") { exchange -> redirect(exchange, "http://localhost:$port/landed") }
            createContext("/to-https") { exchange -> redirect(exchange, "https://127.0.0.1:$port/landed") }
            createContext("/landed") { exchange ->
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            }
            start()
        }
        val client = DefaultSourceHttpClient(maxRetries = 0)

        val (crossHost, toHttps) = try {
            val base = "http://127.0.0.1:${server.address.port}"
            client.get("$base/cross-host").statusCode to runCatching { client.get("$base/to-https").statusCode }.getOrNull()
        } finally {
            server.stop(0)
        }

        assertEquals(200, crossHost)
        assertEquals(302, toHttps)
    }

    @Test
    fun `same-host requests beyond OkHttp's default per-host limit run at once (ADR 0051)`() = runBlocking {
        val burst = 8
        val arrived = CountDownLatch(burst)
        val handlers = Executors.newFixedThreadPool(burst)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = handlers
            createContext("/page") { exchange ->
                arrived.countDown()
                val body = (if (arrived.await(2, TimeUnit.SECONDS)) "together" else "queued").toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        val client = DefaultSourceHttpClient(maxRetries = 0)

        val bodies = try {
            List(burst) { async { client.get("http://127.0.0.1:${server.address.port}/page").body } }.awaitAll()
        } finally {
            server.stop(0)
            handlers.shutdownNow()
        }

        assertEquals(List(burst) { "together" }, bodies)
        for (dispatcher in listOf(unqueuedSourceDispatcher(), defaultSourceOkHttpClient.dispatcher)) {
            assertEquals(128, dispatcher.maxRequests)
            assertEquals(128, dispatcher.maxRequestsPerHost)
        }
    }

    @Test
    fun `a request without its own agent carries the platform http agent (ADR 0051)`() = runBlocking {
        val seenAgents = mutableListOf<String?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                seenAgents += exchange.requestHeaders.getFirst("User-Agent")
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            }
            start()
        }
        val previous = System.setProperty("http.agent", "Platform/1.0")
        try {
            val client = DefaultSourceHttpClient(maxRetries = 0)
            val url = "http://127.0.0.1:${server.address.port}/"
            client.get(url)
            client.get(url, headers = mapOf("user-agent" to "Source/2.0"))
            client.postForm(url, form = mapOf("a" to "1"))
            client.postJson(url, body = "{}")
        } finally {
            if (previous == null) System.clearProperty("http.agent") else System.setProperty("http.agent", previous)
            server.stop(0)
        }

        assertEquals(listOf<String?>("Platform/1.0", "Source/2.0", "Platform/1.0", "Platform/1.0"), seenAgents)
    }

    private fun redirect(exchange: HttpExchange, location: String) {
        exchange.responseHeaders.add("Location", location)
        exchange.sendResponseHeaders(302, -1)
        exchange.close()
    }

    @Test
    fun `retry delay honors a short retry after header`() {
        assertEquals(2_000L, retryDelayMs(mapOf("Retry-After" to listOf("2")), attempt = 2, baseDelayMs = 300L))
    }

    @Test
    fun `a long retry after is not waited out (ADR 0051)`() {
        assertNull(retryDelayMs(mapOf("Retry-After" to listOf("3600")), attempt = 1, baseDelayMs = 300L))
    }

    @Test
    fun `retry delay falls back to linear backoff when retry after missing`() {
        assertEquals(600L, retryDelayMs(emptyMap(), attempt = 2, baseDelayMs = 300L))
    }
}
