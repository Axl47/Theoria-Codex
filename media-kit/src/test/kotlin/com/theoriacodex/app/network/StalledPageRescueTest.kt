package com.theoriacodex.app.network

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StalledPageRescueTest {
    private val page = ByteArray(4_096) { (it % 251).toByte() }
    private val requests = CopyOnWriteArrayList<String?>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = Executors.newCachedThreadPool()
        createContext("/page") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            requests += range
            if (range == null) {
                exchange.sendResponseHeaders(200, page.size.toLong())
                exchange.responseBody.write(page, 0, STALLS_AFTER)
                exchange.responseBody.flush()
                Thread.sleep(STALL_LENGTH_MS)
            } else {
                exchange.sendResponseHeaders(206, (page.size - STALLS_AFTER).toLong())
                exchange.responseBody.write(page, STALLS_AFTER, page.size - STALLS_AFTER)
            }
            runCatching { exchange.close() }
        }
        start()
    }

    @After
    fun stop() = server.stop(0)

    @Test
    fun `a page that goes quiet carries on from its last byte (ADR 0102)`() {
        val client = OkHttpClient.Builder()
            .addInterceptor(StalledPageRescue(rescue = { OkHttpClient() }, stallMs = 200, appliesTo = { true }))
            .build()

        val body = client.newCall(Request.Builder().url("http://127.0.0.1:${server.address.port}/page").build())
            .execute().use { it.body.bytes() }

        assertEquals(page.toList(), body.toList())
        assertEquals(listOf(null, "bytes=$STALLS_AFTER-"), requests.toList())
    }

    @Test
    fun `a page that stalls twice reports the failure`() {
        val stalled = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long = throw SocketTimeoutException("timeout")
            override fun timeout() = Timeout.NONE
            override fun close() = Unit
        }
        val source = ResumingSource(stalled) { stalled }.buffer()

        assertThrows(SocketTimeoutException::class.java) { source.readByteArray() }
    }

    @Test
    fun `a rescue stops reading once the original call is cancelled (ADR 0102)`() {
        val original = OkHttpClient().newCall(Request.Builder().url("http://127.0.0.1/").build())
        val source = StopsWithCall(Buffer().writeUtf8("page"), original)
        val sink = Buffer()
        assertEquals(1L, source.read(sink, 1))

        original.cancel()

        assertThrows(IOException::class.java) { source.read(sink, 1) }
        assertEquals("p", sink.readUtf8())
    }

    @Test
    fun `only nhentai page hosts are rescued unless told otherwise (ADR 0102)`() {
        val local = Dns { listOf(InetAddress.getByName("127.0.0.1")) }
        val rescuer = OkHttpClient.Builder().dns(local).build()
        val client = OkHttpClient.Builder().dns(local)
            .addInterceptor(StalledPageRescue(rescue = { rescuer }, stallMs = 200))
            .build()
        fun fetch(host: String) = runCatching {
            client.newCall(Request.Builder().url("http://$host:${server.address.port}/page").build()).execute().use { it.body.bytes() }
        }

        fetch("img.example.org")
        assertEquals(listOf<String?>(null), requests.toList())

        requests.clear()
        assertEquals(page.toList(), fetch("i3.nhentai.net").getOrThrow().toList())
        assertEquals(listOf(null, "bytes=$STALLS_AFTER-"), requests.toList())
    }

    private companion object {
        const val STALLS_AFTER = 1_024
        const val STALL_LENGTH_MS = 1_500L
    }
}
