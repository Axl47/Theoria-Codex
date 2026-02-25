package com.theoriacodex.sources.http

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.test.runTest
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
}
