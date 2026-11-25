package com.theoriacodex.sources.http

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

class CancellableHttpConnectionTest {
    @Test
    fun `cancelling the caller returns at once and disconnects a stalled read (ADR 0051)`() = runBlocking {
        val handlerStarted = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/stalled") { exchange ->
                exchange.sendResponseHeaders(200, 0L)
                handlerStarted.countDown()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
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
        val connection = URI("http://127.0.0.1:${server.address.port}/stalled").toURL().openConnection() as HttpURLConnection
        connection.readTimeout = 10_000
        val call = launch(Dispatchers.IO) {
            executeCancellableHttpConnection(connection) { connection.inputStream.use { it.readBytes() } }
        }
        val disconnectedInTime = try {
            assertTrue("the request never reached the server", handlerStarted.await(5L, TimeUnit.SECONDS))
            withTimeout(1_000L) { call.cancel(); call.join() }
            disconnected.await(2L, TimeUnit.SECONDS)
        } finally {
            server.stop(0)
        }

        assertTrue(call.isCancelled)
        assertTrue("the connection kept reading after its caller was cancelled", disconnectedInTime)
    }
}
