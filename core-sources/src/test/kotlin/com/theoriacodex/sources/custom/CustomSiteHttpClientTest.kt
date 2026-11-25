package com.theoriacodex.sources.custom

import com.sun.net.httpserver.HttpServer
import com.theoriacodex.domain.diagnostics.Diagnostics
import com.theoriacodex.domain.source.ProbeFailure
import com.theoriacodex.sources.http.HttpRequestSpec
import com.theoriacodex.sources.http.SourceByteResponse
import com.theoriacodex.sources.http.HostUnavailableException
import com.theoriacodex.sources.http.HostOutageTracker
import com.theoriacodex.sources.http.HostOutageKind
import com.theoriacodex.sources.http.SourceRetryPolicy
import com.theoriacodex.sources.http.defaultSourceOkHttpClient
import com.theoriacodex.sources.http.httpTransport
import java.io.IOException
import java.net.InetSocketAddress
import okhttp3.OkHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomSiteHttpClientTest {
    private val requested = mutableListOf<String>()

    private fun client(routes: Map<String, SourceByteResponse>) = CustomSiteHttpClient(3) { spec ->
        requested += spec.url
        routes[spec.url] ?: SourceByteResponse(200, "ok".toByteArray())
    }

    private fun redirect(location: String, status: Int = 302) =
        SourceByteResponse(status, ByteArray(0), mapOf("location" to listOf(location)))

    @Test
    fun `follows same-site HTTPS redirects, including relative ones and the www twin (ADR 0051)`() = runTest {
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
    fun `refuses a redirect to plain HTTP without requesting it (ADR 0051)`() = runTest {
        val http = client(mapOf("https://booru.example/a?api_key=k" to redirect("http://booru.example/a?api_key=k")))

        val failure = runCatching { http.get("https://booru.example/a", mapOf("api_key" to "k")) }.exceptionOrNull()

        assertEquals(ProbeFailure.INSECURE_REDIRECT, (failure as CustomSiteRedirectException).failure)
        assertEquals(listOf("https://booru.example/a?api_key=k"), requested)
    }

    @Test
    fun `refuses redirects to another host or port without requesting them (ADR 0051)`() = runTest {
        listOf(
            "https://evil.example/a",
            "https://booru.example.evil.example/a",
            "https://cdn.booru.example/a",
            "https://booru.example:8443/a",
        ).forEach { target ->
            requested.clear()
            val http = client(mapOf("https://booru.example/a" to redirect(target)))

            val failure = runCatching { http.get("https://booru.example/a") }.exceptionOrNull()

            assertEquals(target, ProbeFailure.CROSS_HOST_REDIRECT, (failure as? CustomSiteRedirectException)?.failure)
            assertEquals(listOf("https://booru.example/a"), requested)
        }
    }

    @Test
    fun `stops after three redirect hops (ADR 0040, 0051)`() = runTest {
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
            httpTransport(defaultSourceOkHttpClient, 1_000, 1_000, followRedirects = false)(
                HttpRequestSpec("GET", "http://127.0.0.1:${server.address.port}/start", emptyMap(), null, 1_024),
            )
        } finally {
            server.stop(0)
        }

        assertEquals(302, response.statusCode)
        assertEquals(0, followedHits)
    }

    private fun <T> withLoopbackSite(
        answer: (path: String, hit: Int, port: Int) -> Pair<Int, String?>,
        use: suspend (OkHttpClient) -> T,
    ): Pair<T, List<String>> {
        val hits = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val path = exchange.requestURI.toString()
                synchronized(hits) { hits += path }
                val (status, location) = answer(path, hits.size, address.port)
                location?.let { exchange.responseHeaders.add("Location", it) }
                exchange.sendResponseHeaders(status, -1)
                exchange.close()
            }
            start()
        }
        val port = server.address.port
        val loopback = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url.newBuilder().scheme("http").host("127.0.0.1").port(port).build()
            chain.proceed(chain.request().newBuilder().url(url).build())
        }.build()
        val result = try {
            kotlinx.coroutines.runBlocking { use(loopback) }
        } finally {
            server.stop(0)
        }
        return result to synchronized(hits) { hits.toList() }
    }

    @Test
    fun `the shipped client never lets the transport follow a redirect (ADR 0051)`() {
        val (failure, hits) = withLoopbackSite({ path, _, port ->
            if (path.startsWith("/a")) 302 to "http://127.0.0.1:$port/stolen?api_key=k" else 200 to null
        }) { okHttp ->
            runCatching { CustomSiteHttpClient(okHttpClient = okHttp).get("https://booru.example/a", mapOf("api_key" to "k")) }.exceptionOrNull()
        }

        assertEquals(listOf("/a?api_key=k"), hits)
        assertEquals(ProbeFailure.INSECURE_REDIRECT, (failure as? CustomSiteRedirectException)?.failure)
    }

    @Test
    fun `the shipped client follows three hops and retries a transient 503 once outages are tracked (ADR 0040, 0050, 0051)`() {
        val (hopFailure, hops) = withLoopbackSite({ path, _, _ -> 302 to "/${path.removePrefix("/").toInt() + 1}" }) { okHttp ->
            runCatching { CustomSiteHttpClient(okHttpClient = okHttp).get("https://booru.example/0") }.exceptionOrNull()
        }
        val (status, attempts) = withLoopbackSite({ _, hit, _ -> (if (hit == 1) 503 else 200) to null }) { okHttp ->
            runCatching { CustomSiteHttpClient(outages = HostOutageTracker(), okHttpClient = okHttp).get("https://booru.example/index.php").statusCode }
        }

        assertTrue(hopFailure is IOException && hopFailure !is CustomSiteRedirectException)
        assertEquals(listOf("/0", "/1", "/2", "/3"), hops)
        assertEquals(2, attempts.size)
        assertEquals(200, status.getOrNull())
    }

    @Test
    fun `a challenge served instead of the API marks the site and later requests skip the network`() = runTest {
        val tracker = HostOutageTracker()
        val challenge = "<!doctype html><html><title>Checking</title><p>Complete the challenge to show you are not a robot.</p></html>"
        val http = CustomSiteHttpClient(3, tracker) { spec ->
            requested += spec.url
            SourceByteResponse(200, challenge.toByteArray())
        }

        val first = runCatching { http.get("https://gated.example/index.php", mapOf("page" to "dapi")) }.exceptionOrNull()
        val second = runCatching { http.get("https://gated.example/index.php", mapOf("page" to "dapi")) }.exceptionOrNull()

        assertEquals(HostOutageKind.CHALLENGE, (first as HostUnavailableException).kind)
        assertEquals(HostOutageKind.CHALLENGE, (second as HostUnavailableException).kind)
        assertEquals(1, requested.size)
    }

    @Test
    fun `a real answer proves the site works and a refusal of its keys takes that back`() = runTest {
        val tracker = HostOutageTracker()
        var status = 200
        val http = CustomSiteHttpClient(3, tracker) { SourceByteResponse(status, "[]".toByteArray()) }

        http.get("https://booru.example/index.php")
        assertEquals(setOf("booru.example"), tracker.working.value)

        status = 404
        http.get("https://booru.example/index.php")
        assertEquals("a missing post says nothing about the site", setOf("booru.example"), tracker.working.value)

        status = 401
        http.get("https://booru.example/index.php")
        assertTrue(tracker.working.value.isEmpty())
    }

    private fun retryingClient(tracker: HostOutageTracker? = null, retry: SourceRetryPolicy? = SourceRetryPolicy(), answer: (Int) -> SourceByteResponse) =
        CustomSiteHttpClient(3, tracker, retry) { spec ->
            requested += spec.url
            answer(requested.size)
        }

    @Test
    fun `a transient 503 is retried and the next answer wins (ADR 0050)`() = runTest {
        val tracker = HostOutageTracker()
        val http = retryingClient(tracker) { attempt ->
            if (attempt == 1) SourceByteResponse(503, ByteArray(0)) else SourceByteResponse(200, "ok".toByteArray())
        }

        val response = http.get("https://booru.example/index.php")

        assertEquals("ok", response.body)
        assertEquals(2, requested.size)
        assertTrue("the recovered request leaves no outage", tracker.outages.value.isEmpty())
    }

    @Test
    fun `transport errors are retried and an exhausted retry surfaces the last one`() = runTest {
        val http = retryingClient { throw IOException("reset") }

        val failure = runCatching { http.get("https://booru.example/index.php") }.exceptionOrNull()

        assertEquals("reset", (failure as IOException).message)
        assertEquals(3, requested.size)
    }

    @Test
    fun `a 503 that outlasts the retries marks the site down`() = runTest {
        val http = retryingClient(HostOutageTracker()) { SourceByteResponse(503, ByteArray(0)) }

        val failure = runCatching { http.get("https://booru.example/index.php") }.exceptionOrNull()

        assertEquals(HostOutageKind.MAINTENANCE, (failure as HostUnavailableException).kind)
        assertEquals(3, requested.size)
    }

    @Test
    fun `a probe without a retry policy sees the first answer raw (ADR 0040)`() = runTest {
        val http = retryingClient(retry = null) { SourceByteResponse(503, ByteArray(0)) }

        assertEquals(503, http.get("https://booru.example/index.php").statusCode)
        assertEquals(1, requested.size)
    }

    @Test
    fun `an adapter's failed answer reaches the diagnostics log and a probe's does not`() = runTest {
        val logged = mutableListOf<String>()
        Diagnostics.install { area, message, _ -> logged += "$area: $message" }
        try {
            retryingClient(retry = null) { SourceByteResponse(404, ByteArray(0)) }.get("https://probe.example/index.php")
            retryingClient(HostOutageTracker()) { SourceByteResponse(404, ByteArray(0)) }.get("https://booru.example/index.php")
        } finally {
            Diagnostics.install { _, _, _ -> }
        }

        assertEquals(listOf("http: booru.example: HTTP 404"), logged)
    }
}
