package com.theoriacodex.sources.http

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostOutageTrackerTest {
    private var now = 1_000L
    private val tracker = HostOutageTracker(clock = { now }, outageMs = 60_000L)

    @Test fun `a passed check clears the challenge and is announced, an expired one is not`() = runTest {
        val passed = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { tracker.passes.toList(passed) }
        tracker.record("https://www.site.test/", HostOutageKind.CHALLENGE)
        tracker.record("https://old.test/", HostOutageKind.CHALLENGE)
        now += 60_000L

        assertFalse(tracker.isChallenged("https://old.test/"))
        tracker.pass("WWW.site.test")

        assertEquals(listOf("site.test"), passed)
        assertEquals(setOf("old.test"), tracker.outages.value.keys)
    }

    @Test fun `down statuses and challenges are outages, ordinary failures are not`() {
        assertEquals(HostOutageKind.MAINTENANCE, tracker.recordResponse("https://www.danbooru.donmai.us/x", 503, emptyMap()))
        assertEquals(HostOutageKind.MAINTENANCE, tracker.recordResponse("https://a.example/", 522, emptyMap()))
        assertEquals(
            HostOutageKind.CHALLENGE,
            tracker.recordResponse("https://b.example/", 403, mapOf("CF-Mitigated" to listOf("challenge"))),
        )
        assertNull(tracker.recordResponse("https://c.example/", 500, emptyMap()))
        assertNull(tracker.recordResponse("https://c.example/", 403, emptyMap()))
        assertEquals(setOf("danbooru.donmai.us", "a.example", "b.example"), tracker.outages.value.keys)
    }

    @Test fun `an outage refuses requests until it expires or is cleared (ADR 0050)`() {
        tracker.record("https://danbooru.donmai.us/posts.json", HostOutageKind.MAINTENANCE)
        val refused = runCatching { tracker.ensureAvailable("https://danbooru.donmai.us/tags.json") }.exceptionOrNull()
        assertEquals(HostOutageKind.MAINTENANCE, (refused as HostUnavailableException).kind)
        tracker.clear("www.danbooru.donmai.us")
        tracker.ensureAvailable("https://danbooru.donmai.us/tags.json")
        tracker.record("https://danbooru.donmai.us/", HostOutageKind.MAINTENANCE)
        now += 60_001L
        tracker.ensureAvailable("https://danbooru.donmai.us/")
        assertTrue(tracker.outages.value.isEmpty())
    }

    @Test fun `a challenge keeps the request it refused, is not renewed while active, and counts as challenged until it expires (ADR 0050)`() {
        val refused = RefusedRequest("https://api.site.test/list", "agent/1")
        tracker.record(refused.url, HostOutageKind.CHALLENGE, refused)
        tracker.record("https://down.test/", HostOutageKind.MAINTENANCE)

        assertEquals(refused, tracker.outages.value["api.site.test"]?.refused)
        assertTrue(tracker.isChallenged("https://api.site.test/other"))
        assertFalse(tracker.isChallenged("https://down.test/"))
        now += 60_001L
        assertFalse(tracker.isChallenged("https://api.site.test/other"))

        val first = RefusedRequest("https://cf.test/a", "agent/1")
        tracker.recordChallenge(first.url, first)
        now += 10_000L
        tracker.recordChallenge("https://cf.test/b", RefusedRequest("https://cf.test/b", "agent/2"))
        assertEquals(HostOutage(HostOutageKind.CHALLENGE, now - 10_000L + 60_000L, first), tracker.outages.value["cf.test"])

        tracker.record("https://bare.test/", HostOutageKind.CHALLENGE)
        val gained = RefusedRequest("https://bare.test/c", "agent/3")
        tracker.recordChallenge(gained.url, gained)
        assertEquals(gained, tracker.outages.value["bare.test"]?.refused)
    }

    @Test fun `an unresolved domain counts only while online (ADR 0050)`() {
        var online = false
        val gated = HostOutageTracker(clock = { now }, isOnline = { online })
        gated.recordFailure("https://gone.example/", UnknownHostException("gone.example"))
        now += 10_000L
        gated.recordFailure("https://gone.example/", UnknownHostException("gone.example"))
        assertTrue(gated.outages.value.isEmpty())
        online = true
        gated.recordFailure("https://gone.example/", UnknownHostException("gone.example"))
        assertTrue("an offline miss counted toward the two", gated.outages.value.isEmpty())
        now += 10_000L
        gated.recordFailure("https://gone.example/", UnknownHostException("gone.example"))
        assertEquals(HostOutageKind.UNRESOLVED, gated.outages.value["gone.example"]?.kind)
    }

    @Test fun `one blip of failed lookups marks nothing, separated failures do (ADR 0050)`() {
        val miss = UnknownHostException("gone.example")
        tracker.recordFailure("https://gone.example/", miss)
        now += 1_000L
        tracker.recordFailure("https://gone.example/", miss)
        assertTrue("two misses in one blip", tracker.outages.value.isEmpty())

        now += 10 * 60 * 1000L
        tracker.recordFailure("https://gone.example/", miss)
        assertTrue("too long after the first to relate to it", tracker.outages.value.isEmpty())

        now += 10_000L
        tracker.recordFailure("https://gone.example/", miss)
        assertEquals(HostOutageKind.UNRESOLVED, tracker.outages.value["gone.example"]?.kind)
    }

    @Test fun `a working host stops being one on an outage or when taken back`() {
        tracker.setWorking("https://www.a.example/posts.json", true)
        assertEquals(setOf("a.example"), tracker.working.value)
        tracker.record("https://a.example/", HostOutageKind.CHALLENGE)
        assertTrue(tracker.working.value.isEmpty())

        tracker.setWorking("https://b.example/", true)
        tracker.setWorking("https://b.example/", false)
        assertTrue(tracker.working.value.isEmpty())
    }

    @Test fun `a Cloudflare challenge served as a 503 is a challenge, not maintenance, and is not retried (ADR 0050)`() = runBlocking {
        val hits = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                hits.incrementAndGet()
                exchange.responseHeaders.add("cf-mitigated", "challenge")
                exchange.sendResponseHeaders(503, -1)
                exchange.close()
            }
            start()
        }
        val outages = HostOutageTracker()
        val url = "http://127.0.0.1:${server.address.port}/posts.json"
        try {
            val refused = runCatching { DefaultSourceHttpClient(maxRetries = 2, outages = outages).get(url) }.exceptionOrNull()
            assertEquals(HostOutageKind.CHALLENGE, (refused as HostUnavailableException).kind)
            assertEquals(1, hits.get())
            assertTrue(outages.isChallenged(url))
        } finally {
            server.stop(0)
        }
    }

    @Test fun `a maintenance page fails at once and the next request never reaches the host (ADR 0050, 0051)`() = runBlocking {
        val hits = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                hits.incrementAndGet()
                exchange.responseHeaders.add("Retry-After", "30")
                exchange.sendResponseHeaders(503, -1)
                exchange.close()
            }
            start()
        }
        val client = DefaultSourceHttpClient(maxRetries = 2, outages = HostOutageTracker())
        val url = "http://127.0.0.1:${server.address.port}/posts.json"
        try {
            val elapsed = measureTimeMillis {
                assertTrue(runCatching { client.get(url) }.exceptionOrNull() is HostUnavailableException)
            }
            assertTrue("Retry-After 30 must not be waited out (took ${elapsed}ms)", elapsed < 5_000L)
            assertTrue(runCatching { client.get(url) }.exceptionOrNull() is HostUnavailableException)
            assertEquals(1, hits.get())
        } finally {
            server.stop(0)
        }
    }
}

class SiteHealthCheckTest {
    private val tracker = HostOutageTracker()

    private fun check(status: Int, body: String = "", headers: Map<String, List<String>> = emptyMap()) =
        SiteHealthCheck(tracker) { SourceByteResponse(status, body.toByteArray(), headers) }

    @Test fun `maintenance marks the site, a healthy answer clears it`() = runBlocking {
        check(503).check("https://danbooru.donmai.us/", emptyMap(), countChallenges = false)
        assertEquals(HostOutageKind.MAINTENANCE, tracker.outages.value["danbooru.donmai.us"]?.kind)
        check(200, "<html>ok</html>").check("https://danbooru.donmai.us/", emptyMap(), countChallenges = false)
        assertTrue(tracker.outages.value.isEmpty())
    }

    @Test fun `a browser check reports a challenge or outage and leaves the tracker alone (ADR 0050)`() = runBlocking {
        tracker.record("https://site.example/", HostOutageKind.CHALLENGE)
        val cf = mapOf("cf-mitigated" to listOf("challenge"))
        assertFalse(check(403, headers = cf).clearsChallenge("https://site.example/", emptyMap()))
        assertFalse(check(503).clearsChallenge("https://site.example/", emptyMap()))
        assertTrue(check(200, "<html>Complete the challenge to show you are not a robot</html>").clearsChallenge("https://site.example/", emptyMap()))
        assertEquals(setOf("site.example"), tracker.outages.value.keys)
    }

    @Test fun `a browser check that can't reach the site says so instead of reporting a refusal`() = runBlocking {
        val unreachable = SiteHealthCheck(tracker) { throw java.net.SocketTimeoutException() }
        assertTrue(runCatching { unreachable.clearsChallenge("https://site.example/", emptyMap()) }.exceptionOrNull() is IOException)
    }

    @Test fun `a verified site's browser agent replaces the request's own and proves the host works (ADR 0050)`() = runBlocking {
        val seenAgents = mutableListOf<String?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                seenAgents += exchange.requestHeaders.getFirst("User-Agent")
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            }
            start()
        }
        val client = DefaultSourceHttpClient(outages = tracker, userAgentFor = { "WebView/2" })
        try {
            client.get("http://127.0.0.1:${server.address.port}/", headers = mapOf("user-agent" to "Mozilla/5.0"))
        } finally {
            server.stop(0)
        }
        assertEquals(listOf<String?>("WebView/2"), seenAgents)
        assertEquals(setOf("127.0.0.1"), tracker.working.value)
    }

    @Test fun `an oversized home page counts as answered and clears a standing outage (ADR 0050)`() = runBlocking {
        val oversized = SiteHealthCheck(tracker) { throw SourceHttpBodyTooLargeException(1) }
        tracker.record("https://site.example/", HostOutageKind.MAINTENANCE)

        oversized.check("https://site.example/", emptyMap(), countChallenges = true)

        assertTrue(tracker.outages.value.isEmpty())
        assertTrue(oversized.clearsChallenge("https://site.example/", emptyMap()))
    }

    @Test fun `challenges count only for user-added sites (ADR 0050)`() = runBlocking {
        val cf = mapOf("cf-mitigated" to listOf("challenge"))
        check(403, headers = cf).check("https://builtin.example/", emptyMap(), countChallenges = false)
        check(200, "<html>Verify you are not a robot to pass this challenge</html>").check("https://builtin.example/", emptyMap(), countChallenges = false)
        assertTrue(tracker.outages.value.isEmpty())
        check(200, "<html>Verify you are not a robot to pass this challenge</html>").check("https://custom.example/", emptyMap(), countChallenges = true)
        assertEquals(HostOutageKind.CHALLENGE, tracker.outages.value["custom.example"]?.kind)
        tracker.clear("custom.example")
        check(403, headers = cf).check("https://custom.example/", emptyMap(), countChallenges = true)
        assertEquals(HostOutageKind.CHALLENGE, tracker.outages.value["custom.example"]?.kind)
    }
}
