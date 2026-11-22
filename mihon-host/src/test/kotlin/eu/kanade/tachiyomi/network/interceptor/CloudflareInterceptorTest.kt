package eu.kanade.tachiyomi.network.interceptor

import com.theoriacodex.domain.adapter.BrowserCheck
import com.theoriacodex.mihonhost.BrowserCheckException
import com.theoriacodex.mihonhost.ChallengeBrowser
import com.theoriacodex.mihonhost.answering
import com.theoriacodex.mihonhost.assertFailsWith
import com.theoriacodex.mihonhost.cannedResponse
import com.theoriacodex.sources.http.BrowserCookieStore
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

    /** What the hidden browser leaves in the store when it loads a challenge; null leaves it as it is. */
    private var earned: String? = "new"
    private val browser = ChallengeBrowser { request, passed ->
        passes += request
        earned?.let { cookies.values["cf_clearance"] = it }
        passed()
    }

    /** The site's answers, in order; once only one is left it is repeated. */
    private val answers = ArrayDeque<(Request) -> Response>()
    private val client = OkHttpClient.Builder()
        .addInterceptor(CloudflareInterceptor(cookies, browser) { clock })
        .addInterceptor(answering { request -> (if (answers.size > 1) answers.removeFirst() else answers.first())(request) })
        .build()

    @Test
    fun `a refusal from any other server passes through untouched`() {
        answers += { cannedResponse(it, code = 403) }

        assertEquals(403, call("https://site.test/").code)
        assertEquals(emptyList<Request>(), passes)
    }

    @Test
    fun `a Cloudflare challenge is passed out of sight with the request's agent, then sent once more`() {
        cookies.values["cf_clearance"] = "old"
        answers += ::challenge
        answers += { cannedResponse(it, body = "listing") }

        assertEquals("listing", call("https://site.test/list").body.string())
        assertEquals(listOf("agent/1"), passes.map { it.header("User-Agent") })
        assertEquals("new", cookies.values["cf_clearance"])
    }

    @Test
    fun `the refused cookie coming back does not count as passing`() {
        cookies.values["cf_clearance"] = "old"
        earned = "old"
        answers += ::challenge

        val failure = assertFailsWith<BrowserCheckException> { call("https://site.test/list") }

        assertEquals(BrowserCheck("https://site.test/list", "agent/1"), failure.check)
    }

    @Test
    fun `a cookie the site still refuses leaves only the visible check`() {
        answers += ::challenge

        assertFailsWith<BrowserCheckException> { call("https://site.test/") }
        assertEquals(1, passes.size)
    }

    @Test
    fun `a host whose check failed fails at once until it answers or a few minutes pass`() {
        earned = null
        answers += ::challenge

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
        answers += { cannedResponse(it) }
        answers += ::challenge
        call("https://site.test/d").close()
        assertFailsWith<BrowserCheckException> { call("https://site.test/e") }
        assertEquals(4, passes.size)
        assertNull(cookies.values["cf_clearance"])
    }

    private fun call(url: String): Response =
        client.newCall(Request.Builder().url(url).header("User-Agent", "agent/1").build()).execute()

    private fun challenge(request: Request): Response =
        cannedResponse(request, code = 403).newBuilder().header("Server", "cloudflare").build()

    /** One site's cookies by name, written and expired as the WebView's store would. */
    private class FakeCookies : BrowserCookieStore {
        val values = linkedMapOf<String, String>()

        override fun cookieHeader(url: String): String? = values.entries.joinToString("; ") { "${it.key}=${it.value}" }.ifEmpty { null }

        override fun setCookie(url: String, setCookie: String) {
            val (name, value) = setCookie.substringBefore(';').split('=', limit = 2)
            values[name] = value
        }

        override fun flush() = Unit

        override fun expire(url: String, name: String?) {
            if (name == null) values.clear() else values.remove(name)
        }
    }
}
