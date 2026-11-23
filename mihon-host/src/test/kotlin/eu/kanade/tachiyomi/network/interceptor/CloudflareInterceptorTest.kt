package eu.kanade.tachiyomi.network.interceptor

import com.theoriacodex.domain.adapter.BrowserCheck
import com.theoriacodex.mihonhost.BrowserCheckException
import com.theoriacodex.mihonhost.ChallengeBrowser
import com.theoriacodex.mihonhost.answering
import com.theoriacodex.mihonhost.assertFailsWith
import com.theoriacodex.mihonhost.cannedResponse
import com.theoriacodex.sources.http.BrowserCookieStore
import java.io.IOException
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CloudflareInterceptorTest {
    private val cookies = FakeCookies()
    private var clock = 0L
    private val passes = mutableListOf<Request>()
    private val sent = mutableListOf<String>()

    /** What the hidden browser leaves in the store when it loads a challenge; null leaves it as it is. */
    private var earned: String? = "new"
    private var mainThread = false
    private val browser = object : ChallengeBrowser {
        override fun mayWait() = !mainThread

        override fun pass(request: Request, passed: () -> Boolean, cancelled: () -> Boolean): Boolean {
            passes += request
            earned?.let { cookies.values["cf_clearance"] = it }
            return passed()
        }
    }

    /** The site's answers to what was sent, cookies included, in order; once only one is left it is repeated. */
    private val answers = ArrayDeque<(Interceptor.Chain, Request) -> Response>()
    private val client = OkHttpClient.Builder()
        .addInterceptor(CloudflareInterceptor(cookies, browser) { clock })
        .addInterceptor { chain ->
            // Stands in for the cookie jar, which adds the store's cookies after application interceptors.
            val request = chain.request().newBuilder().apply { cookies.cookieHeader("")?.let { header("Cookie", it) } }.build()
            sent += request.url.toString()
            (if (answers.size > 1) answers.removeFirst() else answers.first())(chain, request)
        }
        .build()

    @Test
    fun `a refusal that is not Cloudflare's challenge passes through untouched`() {
        cookies.values["cf_clearance"] = "valid"
        answers += { _, request -> cannedResponse(request, code = 403) }
        answers += { _, request -> refusal(request, 403, challenge = false) }
        answers += { _, request -> refusal(request, 503, challenge = false) }

        assertEquals(listOf(403, 403, 503), List(3) { call("https://site.test/").code })
        assertEquals(emptyList<Request>(), passes)
        assertEquals("valid", cookies.values["cf_clearance"])
    }

    @Test
    fun `a challenge is passed out of sight with the request's agent, then sent once more`() {
        cookies.values["cf_clearance"] = "old"
        answers += { _, request -> challenge(request) }
        answers += { _, request -> cannedResponse(request, body = "listing") }

        assertEquals("listing", call("https://site.test/list").body.string())
        assertEquals(listOf("agent/1"), passes.map { it.header("User-Agent") })
        assertEquals("new", cookies.values["cf_clearance"])
        assertEquals(1, cookies.flushes)
    }

    @Test
    fun `the host that challenged after a redirect is the one passed, and the original request is sent again`() {
        answers += { _, request -> challenge(request.newBuilder().url("https://cdn.other.test/list").build()) }
        answers += { _, request -> cannedResponse(request) }

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
        answers += { _, request -> cannedResponse(request, body = "listing") }

        assertEquals("listing", call("https://site.test/list").body.string())
        assertEquals(emptyList<Request>(), passes)
        assertEquals("fresh", cookies.values["cf_clearance"])
    }

    @Test
    fun `the refused cookie coming back does not count as passing`() {
        cookies.values["cf_clearance"] = "old"
        earned = "old"
        answers += { _, request -> challenge(request) }

        val failure = assertFailsWith<BrowserCheckException> { call("https://site.test/list") }

        assertEquals(BrowserCheck("https://site.test/list", "agent/1"), failure.check)
    }

    @Test
    fun `a cookie the site still refuses leaves only the visible check`() {
        answers += { _, request -> challenge(request) }

        assertFailsWith<BrowserCheckException> { call("https://site.test/") }
        assertEquals(1, passes.size)
    }

    @Test
    fun `a call on the main thread or one cancelled never waits for a pass`() {
        cookies.values["cf_clearance"] = "old"
        mainThread = true
        answers += { _, request -> challenge(request) }
        assertFailsWith<BrowserCheckException> { call("https://site.test/") }

        mainThread = false
        answers.clear()
        answers += { chain, request ->
            chain.call().cancel()
            challenge(request)
        }
        assertFailsWith<IOException> { call("https://site.test/") }

        assertEquals(emptyList<Request>(), passes)
        assertEquals("old", cookies.values["cf_clearance"])
    }

    @Test
    fun `a host whose check failed fails at once until it answers or a few minutes pass`() {
        earned = null
        answers += { _, request -> challenge(request) }

        assertFailsWith<BrowserCheckException> { call("https://site.test/a") }
        assertFailsWith<BrowserCheckException> { call("https://www.site.test/b") }
        assertEquals(1, passes.size)

        assertFailsWith<BrowserCheckException> { call("https://other.test/") }
        assertEquals(2, passes.size)

        clock += CloudflareInterceptor.FAILURE_MEMORY_MS
        assertFailsWith<BrowserCheckException> { call("https://site.test/c") }
        assertEquals(3, passes.size)

        // A check passed in the visible browser: the next request gets through and the failure is forgotten.
        answers.clear()
        answers += { _, request -> cannedResponse(request) }
        answers += { _, request -> challenge(request) }
        call("https://site.test/d").close()
        assertFailsWith<BrowserCheckException> { call("https://site.test/e") }
        assertEquals(4, passes.size)
        assertNull(cookies.values["cf_clearance"])
    }

    private fun call(url: String): Response =
        client.newCall(Request.Builder().url(url).header("User-Agent", "agent/1").header("Authorization", "key").build()).execute()

    private fun challenge(request: Request): Response = refusal(request, 403, challenge = true)

    private fun refusal(request: Request, code: Int, challenge: Boolean): Response =
        cannedResponse(request, code = code).newBuilder().header("Server", "cloudflare")
            .apply { if (challenge) header("cf-mitigated", "challenge") }
            .build()

    /** One site's cookies by name, written and expired as the WebView's store would. */
    private class FakeCookies : BrowserCookieStore {
        val values = linkedMapOf<String, String>()
        var flushes = 0

        override fun cookieHeader(url: String): String? = values.entries.joinToString("; ") { "${it.key}=${it.value}" }.ifEmpty { null }

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
