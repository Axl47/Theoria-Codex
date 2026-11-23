package com.theoriacodex.sources.http

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChallengePassTest {
    private val cookies = FakeCookies()
    private val passes = mutableListOf<Request>()
    private val sent: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    private val challengedHosts = mutableSetOf<String>()
    private val passableHosts = mutableSetOf("site.test", "cdn.other.test")

    /** What the hidden browser leaves in the store when it loads a challenge; null leaves it as it is. */
    private var earned: String? = "new"
    private var mainThread = false
    private var browser: ChallengeBrowser = object : ChallengeBrowser {
        override fun mayWait() = !mainThread

        override fun pass(request: Request, passed: () -> Boolean, cancelled: () -> Boolean): Boolean {
            passes += request
            earned?.let { cookies.values["cf_clearance"] = it }
            return passed()
        }
    }

    /** The site's answers to what was sent, cookies included, in order; once only one is left it is repeated. */
    private val answers = ArrayDeque<(Interceptor.Chain, Request) -> Response>()
    private val client by lazy {
        OkHttpClient.Builder()
            .addInterceptor(ChallengePass(cookies, browser, isChallenged = { HostOutageTracker.hostKey(it) in challengedHosts }, mayPass = passableHosts::contains))
            .addInterceptor { chain ->
                // Stands in for the cookie jar, which adds the store's cookies after application interceptors.
                val request = chain.request().newBuilder().apply { cookies.cookieHeader("")?.let { header("Cookie", it) } }.build()
                sent += request.url.toString()
                (if (answers.size > 1) answers.removeFirst() else answers.first())(chain, request)
            }
            .build()
    }

    @Test
    fun `a refusal that is not Cloudflare's challenge passes through untouched`() {
        cookies.values["cf_clearance"] = "valid"
        answers += { _, request -> canned(request, code = 403) }
        answers += { _, request -> refusal(request, 403, challenge = false) }
        answers += { _, request -> refusal(request, 503, challenge = false) }

        assertEquals(listOf(403, 403, 503), List(3) { call("https://site.test/").use { it.code } })
        assertEquals(emptyList<Request>(), passes)
        assertEquals("valid", cookies.values["cf_clearance"])
    }

    @Test
    fun `a challenge is passed out of sight with the request's agent, then sent once more`() {
        cookies.values["cf_clearance"] = "old"
        answers += { _, request -> challenge(request) }
        answers += { _, request -> canned(request, body = "listing") }

        assertEquals("listing", call("https://site.test/list").body.string())
        assertEquals(listOf("agent/1"), passes.map { it.header("User-Agent") })
        assertEquals("new", cookies.values["cf_clearance"])
        assertEquals(1, cookies.flushes)
    }

    @Test
    fun `the host that challenged after a redirect is the one passed, and the original request is sent again`() {
        answers += { _, request -> challenge(request.newBuilder().url("https://cdn.other.test/list").build()) }
        answers += { _, request -> canned(request) }

        call("https://site.test/list").close()

        assertEquals(listOf("https://cdn.other.test/list"), passes.map { it.url.toString() })
        assertEquals(listOf("https://site.test/list", "https://site.test/list"), sent)
    }

    @Test
    fun `a clearance stored while the challenge was on its way is used, not deleted`() {
        cookies.values["cf_clearance"] = "old"
        answers += { _, request ->
            // Another request's pass finishes after this one left with the old cookie.
            cookies.values["cf_clearance"] = "fresh"
            challenge(request)
        }
        answers += { _, request -> canned(request, body = "listing") }

        assertEquals("listing", call("https://site.test/list").body.string())
        assertEquals(emptyList<Request>(), passes)
        assertEquals("fresh", cookies.values["cf_clearance"])
    }

    @Test
    fun `a pass that earns nothing new answers with the challenge, body intact`() {
        cookies.values["cf_clearance"] = "old"
        earned = "old"
        answers += { _, request -> challenge(request, body = "just a moment") }

        val response = call("https://site.test/list")

        assertTrue(response.isCloudflareChallenge())
        assertEquals("just a moment", response.body.string())
        assertEquals(1, passes.size)
        assertEquals(listOf("https://site.test/list"), sent)
    }

    @Test
    fun `no pass for a host already waiting on the user's check, one not allowed, or a main-thread call`() {
        answers += { _, request -> challenge(request) }
        challengedHosts += "site.test"
        call("https://www.site.test/a").close()
        challengedHosts.clear()

        call("https://never-verified.test/").close()

        mainThread = true
        call("https://site.test/b").close()

        assertEquals(emptyList<Request>(), passes)
    }

    @Test
    fun `a cancelled call never waits for a pass`() {
        cookies.values["cf_clearance"] = "old"
        answers += { chain, request ->
            chain.call().cancel()
            challenge(request)
        }

        assertThrows(IOException::class.java) { call("https://site.test/") }

        assertEquals(emptyList<Request>(), passes)
        assertEquals("old", cookies.values["cf_clearance"])
    }

    @Test
    fun `no more than two hidden browsers run at once, however many hosts challenge`() {
        val running = AtomicInteger()
        val most = AtomicInteger()
        val release = CountDownLatch(1)
        val started = CountDownLatch(ChallengePass.MAX_BROWSERS)
        browser = object : ChallengeBrowser {
            override fun mayWait() = true

            override fun pass(request: Request, passed: () -> Boolean, cancelled: () -> Boolean): Boolean {
                most.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
                running.decrementAndGet()
                return false
            }
        }
        val hosts = listOf("a.test", "b.test", "c.test")
        passableHosts += hosts
        answers += { _, request -> challenge(request) }

        val calls = hosts.map { host -> thread { call("https://$host/").close() } }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        // Long enough for a third browser to start, were it allowed to.
        Thread.sleep(300)
        release.countDown()
        calls.forEach(Thread::join)

        assertEquals(ChallengePass.MAX_BROWSERS, most.get())
    }

    private fun call(url: String): Response =
        client.newCall(Request.Builder().url(url).header("User-Agent", "agent/1").header("Authorization", "key").build()).execute()

    private fun challenge(request: Request, body: String = ""): Response = refusal(request, 403, challenge = true, body = body)

    private fun refusal(request: Request, code: Int, challenge: Boolean, body: String = ""): Response =
        canned(request, code = code, body = body).newBuilder().header("Server", "cloudflare")
            .apply { if (challenge) header("cf-mitigated", "challenge") }
            .build()

    private fun canned(request: Request, body: String = "", code: Int = 200): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("canned")
        .body(body.toResponseBody())
        .build()

    /** One site's cookies by name, written and expired as the WebView's store would. */
    private class FakeCookies : BrowserCookieStore {
        val values: MutableMap<String, String> = java.util.Collections.synchronizedMap(linkedMapOf())
        var flushes = 0

        override fun cookieHeader(url: String): String? =
            synchronized(values) { values.entries.joinToString("; ") { "${it.key}=${it.value}" } }.ifEmpty { null }

        override fun setCookie(url: String, setCookie: String) {
            val (name, value) = setCookie.substringBefore(';').split('=', limit = 2)
            values[name] = value
        }

        override fun flush() {
            flushes++
        }

        override fun expire(url: String, name: String?) {
            if (name == null) values.clear() else values.remove(name)
        }
    }
}
