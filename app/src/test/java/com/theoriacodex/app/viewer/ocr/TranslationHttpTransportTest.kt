package com.theoriacodex.app.viewer.ocr

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationHttpTransportTest {
    @Test(timeout = 10_000L)
    fun `real POST preserves UTF8 and accepts an exact bounded response`() = runBlocking {
        val request = """{"q":["日本語"]}"""
        val response = "語".repeat(21_845) + "!" // 65,536 UTF-8 bytes.
        LoopbackGateway { exchange ->
            assertEquals("POST", exchange.requestMethod)
            assertEquals("application/json; charset=UTF-8", exchange.requestHeaders.getFirst("Content-Type"))
            assertEquals(request, exchange.requestBody.bufferedReader(Charsets.UTF_8).readText())
            exchange.respond(200, response)
        }.use { gateway ->
            assertEquals(
                ViewerTranslationHttpResponse(200, response),
                HttpUrlConnectionTranslationTransport().postJson(gateway.endpoint, request),
            )
        }
    }

    @Test(timeout = 10_000L)
    fun `oversized success and error streams are rejected after the byte limit`() {
        listOf(200, 500).forEach { status ->
            LoopbackGateway { exchange -> exchange.respond(status, "x".repeat(65_537)) }.use { gateway ->
                val failure = assertThrows(IOException::class.java) {
                    runBlocking { HttpUrlConnectionTranslationTransport().postJson(gateway.endpoint, "{}") }
                }
                assertEquals("Translation server response was too large", failure.message)
            }
        }
    }

    @Test(timeout = 10_000L)
    fun `redirect is returned without following or resending phrase data`() = runBlocking {
        val calls = AtomicInteger()
        LoopbackGateway { exchange ->
            calls.incrementAndGet()
            exchange.responseHeaders.add("Location", "/unexpected")
            exchange.respond(302, "")
        }.use { gateway ->
            val result = HttpUrlConnectionTranslationTransport().postJson(gateway.endpoint, "private phrase")
            assertEquals(302, result.statusCode)
            assertEquals(1, calls.get())
        }
    }

    @Test(timeout = 10_000L)
    fun `leaving Viewer cancels a stalled real response without waiting for read timeout`() = runBlocking {
        val waitingForBody = CountDownLatch(1)
        val releaseServer = CountDownLatch(1)
        LoopbackGateway { exchange ->
            exchange.requestBody.use { it.readBytes() }
            exchange.sendResponseHeaders(200, 100)
            exchange.responseBody.flush()
            waitingForBody.countDown()
            releaseServer.await(10, TimeUnit.SECONDS)
        }.use { gateway ->
            val request = async(Dispatchers.Default) {
                HttpUrlConnectionTranslationTransport(readTimeoutMs = 30_000)
                    .postJson(gateway.endpoint, "{}")
            }
            try {
                assertTrue("Gateway received no request", waitingForBody.await(5, TimeUnit.SECONDS))
                withTimeout(2_000) { request.cancelAndJoin() }
                assertTrue(request.isCancelled)
            } finally {
                releaseServer.countDown()
                request.cancelAndJoin()
            }
        }
    }
}

private class LoopbackGateway(handler: (HttpExchange) -> Unit) : AutoCloseable {
    private val executor = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        this.executor = this@LoopbackGateway.executor
        createContext("/") { exchange -> exchange.use { handler(it) } }
        start()
    }
    val endpoint = "http://127.0.0.1:${server.address.port}/translate-batch"

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }
}

private fun HttpExchange.respond(status: Int, body: String) {
    val bytes = body.toByteArray(Charsets.UTF_8)
    sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}
