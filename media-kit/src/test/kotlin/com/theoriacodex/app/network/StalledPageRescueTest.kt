package com.theoriacodex.app.network

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import okhttp3.CookieJar
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
    private val connections = CopyOnWriteArrayList<Int>()
    private val long = ByteArray(1024 * 1024) { (it % 253).toByte() }
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
        createContext("/whole") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            requests += range
            exchange.sendResponseHeaders(200, page.size.toLong())
            if (range == null) {
                exchange.responseBody.write(page, 0, STALLS_AFTER)
                exchange.responseBody.flush()
                Thread.sleep(STALL_LENGTH_MS)
            } else {
                exchange.responseBody.write(page)
            }
            runCatching { exchange.close() }
        }
        createContext("/silent") { exchange ->
            requests += exchange.requestHeaders.getFirst("Range")
            if (requests.size == 1) {
                Thread.sleep(STALL_LENGTH_MS)
            } else {
                exchange.sendResponseHeaders(200, long.size.toLong())
                runCatching { exchange.responseBody.write(long) }
            }
            runCatching { exchange.close() }
        }
        createContext("/long") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            requests += range
            connections += exchange.remoteAddress.port
            if (range == null) {
                exchange.sendResponseHeaders(200, long.size.toLong())
                exchange.responseBody.write(long, 0, STALLS_AFTER)
                exchange.responseBody.flush()
                Thread.sleep(LONG_STALL_MS)
            } else {
                exchange.sendResponseHeaders(206, (long.size - STALLS_AFTER).toLong())
                runCatching { exchange.responseBody.write(long, STALLS_AFTER, long.size - STALLS_AFTER) }
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
    fun `a host that ignores the range and sends the whole page again is read from the same byte (ADR 0102)`() {
        val client = OkHttpClient.Builder()
            .addInterceptor(StalledPageRescue(rescue = { OkHttpClient() }, stallMs = 200, appliesTo = { true }))
            .build()

        val body = client.newCall(Request.Builder().url("http://127.0.0.1:${server.address.port}/whole").build())
            .execute().use { it.body.bytes() }

        assertEquals(page.toList(), body.toList())
        assertEquals(listOf(null, "bytes=$STALLS_AFTER-"), requests.toList())
    }

    @Test
    fun `a page that stalls before its headers is fetched again and stops reading once the call is cancelled (ADR 0102)`() {
        val client = OkHttpClient.Builder()
            .addInterceptor(StalledPageRescue(rescue = { OkHttpClient() }, stallMs = 200, appliesTo = { true }))
            .build()
        val call = client.newCall(Request.Builder().url("http://127.0.0.1:${server.address.port}/silent").build())
        val source = call.execute().body.source()

        assertEquals(long.copyOfRange(0, 64 * 1024).toList(), source.readByteArray(64 * 1024L).toList())
        assertEquals(listOf<String?>(null, null), requests.toList())

        call.cancel()
        assertThrows(IOException::class.java) { source.readByteArray(long.size - 64 * 1024L) }
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
        fetch("static.nhentai.net")
        assertEquals(listOf<String?>(null, null), requests.toList())

        listOf("i3.nhentai.net", "i.nhentai.net").forEach { host ->
            requests.clear()
            assertEquals(page.toList(), fetch(host).getOrThrow().toList())
            assertEquals(listOf(null, "bytes=$STALLS_AFTER-"), requests.toList())
        }
    }

    @Test
    fun `the image client resumes a stalled page on another connection and stops reading once the call is cancelled (ADR 0102)`() {
        val clients = AppHttpClients(
            CookieJar.NO_COOKIES,
            memory = ConnectionMemory(NoRecentHosts, activeNetwork = { null }, resolver = Dns { listOf(InetAddress.getByName("127.0.0.1")) }),
        )
        val call = clients.images.newCall(Request.Builder().url("http://i3.nhentai.net:${server.address.port}/long").build())
        val source = call.execute().body.source()

        assertEquals(long.copyOfRange(0, 64 * 1024).toList(), source.readByteArray(64 * 1024L).toList())
        assertEquals(listOf(null, "bytes=$STALLS_AFTER-"), requests.toList())
        assertEquals(2, connections.toSet().size)

        call.cancel()
        assertThrows(IOException::class.java) { source.readByteArray(long.size - 64 * 1024L) }
    }

    private companion object {
        const val STALLS_AFTER = 1_024
        const val STALL_LENGTH_MS = 1_500L
        const val LONG_STALL_MS = 3_500L
    }
}
