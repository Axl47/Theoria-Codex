package com.theoriacodex.sources.custom

import com.sun.net.httpserver.HttpServer
import com.theoriacodex.domain.source.ProbeFailure
import com.theoriacodex.sources.http.HttpRequestSpec
import com.theoriacodex.sources.http.SourceByteResponse
import com.theoriacodex.sources.http.executeHttpRequest
import java.io.IOException
import java.net.InetSocketAddress
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomSiteHttpClientTest {
    private val requested = mutableListOf<String>()

    /** Serves `routes[url]`, or 200 "ok" for any other URL. */
    private fun client(routes: Map<String, SourceByteResponse>) = CustomSiteHttpClient(1_000, 1_000, 3) { spec ->
        assertEquals(false, spec.followRedirects)
        requested += spec.url
        routes[spec.url] ?: SourceByteResponse(200, "ok".toByteArray())
    }

    private fun redirect(location: String, status: Int = 302) =
        SourceByteResponse(status, ByteArray(0), mapOf("location" to listOf(location)))

    @Test
    fun `follows same-site HTTPS redirects, including relative ones and the www twin`() = runTest {
        val http = client(
            mapOf(
                "https://sakugabooru.com/post.json?limit=1" to redirect("https://www.sakugabooru.com/post.json?limit=1", 301),
                "https://www.sakugabooru.com/post.json?limit=1" to redirect("/api/post.json?limit=1", 307),
            ),
        )

        val response = http.get("https://sakugabooru.com/post.json", mapOf("limit" to "1"))

        assertEquals("ok", response.body)
        assertEquals(
            listOf(
                "https://sakugabooru.com/post.json?limit=1",
                "https://www.sakugabooru.com/post.json?limit=1",
                "https://www.sakugabooru.com/api/post.json?limit=1",
            ),
            requested,
        )
    }

    @Test
    fun `refuses a redirect to plain HTTP without requesting it`() = runTest {
        val http = client(mapOf("https://booru.example/a?api_key=k" to redirect("http://booru.example/a?api_key=k")))

        val failure = runCatching { http.get("https://booru.example/a", mapOf("api_key" to "k")) }.exceptionOrNull()

        assertEquals(ProbeFailure.INSECURE_REDIRECT, (failure as CustomSiteRedirectException).failure)
        assertEquals(listOf("https://booru.example/a?api_key=k"), requested)
    }

    @Test
    fun `refuses redirects to another host or port without requesting them`() = runTest {
        listOf("https://evil.example/a", "https://booru.example.evil.example/a", "https://booru.example:8443/a").forEach { target ->
            requested.clear()
            val http = client(mapOf("https://booru.example/a" to redirect(target)))

            val failure = runCatching { http.get("https://booru.example/a") }.exceptionOrNull()

            assertEquals(target, ProbeFailure.CROSS_HOST_REDIRECT, (failure as CustomSiteRedirectException).failure)
            assertEquals(listOf("https://booru.example/a"), requested)
        }
    }

    @Test
    fun `stops after three redirect hops`() = runTest {
        val http = client((0..3).associate { "https://booru.example/$it" to redirect("/${it + 1}") })

        val failure = runCatching { http.get("https://booru.example/0") }.exceptionOrNull()

        assertTrue(failure is IOException && failure !is CustomSiteRedirectException)
        assertEquals(4, requested.size)
    }

    @Test
    fun `never sends a request that is not HTTPS`() {
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { client(emptyMap()).get("http://booru.example/a") }
        }
        assertEquals(emptyList<String>(), requested)
    }

    @Test
    fun `the shared transport returns a redirect unfollowed when asked`() = runTest {
        var followedHits = 0
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/start") { exchange ->
                exchange.responseHeaders.add("Location", "/elsewhere")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }
            createContext("/elsewhere") { exchange ->
                followedHits += 1
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            }
            start()
        }
        val response = try {
            executeHttpRequest(
                HttpRequestSpec("GET", "http://127.0.0.1:${server.address.port}/start", emptyMap(), null, 1_024, 1_000, 1_000, followRedirects = false),
            )
        } finally {
            server.stop(0)
        }

        assertEquals(302, response.statusCode)
        assertEquals(0, followedHits)
    }
}
