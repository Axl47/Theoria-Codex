package eu.kanade.tachiyomi.network.interceptor

import com.theoriacodex.domain.diagnostics.Diagnostics
import com.theoriacodex.extensionhost.ExtensionHost
import com.theoriacodex.extensionhost.answering
import com.theoriacodex.extensionhost.cannedResponse
import com.theoriacodex.sources.http.BrowserCookieStore
import com.theoriacodex.sources.http.ChallengeBrowser
import com.theoriacodex.sources.http.ChallengeBrowsers
import com.theoriacodex.sources.http.ChallengePass
import com.theoriacodex.sources.http.HostOutageKind
import com.theoriacodex.sources.http.HostOutageTracker
import com.theoriacodex.sources.http.HostUnavailableException
import com.theoriacodex.sources.http.RefusedRequest
import java.io.IOException
import com.theoriacodex.extensionhost.assertFailsWith
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class HostInterceptorsTest {
    private val request = Request.Builder().url("https://example.test/").build()

    @Test
    fun `a non-IO exception later in the chain is wrapped in IOException`() {
        val cause = IllegalStateException("boom")
        val client = clientOf(UncaughtExceptionInterceptor(), Interceptor { throw cause })

        val failure = assertFailsWith<IOException> { client.newCall(request).execute() }

        assertSame(cause, failure.cause)
    }

    @Test
    fun `the client extensions rebuild carries the interceptor names they require, in Mihon's order`() {
        assertEquals(
            listOf("UncaughtExceptionInterceptor", "UserAgentInterceptor", "CloudflareInterceptor"),
            ExtensionHost.extensionClient(NoCookies, ChallengeBrowsers(NoCookies, NoBrowser), HostOutageTracker()).interceptors.map { it.javaClass.simpleName },
        )
    }

    @Test
    fun `a challenge no hidden pass clears is recorded with the agent it refused, and never reaches the extension`() {
        var now = 0L
        val outages = HostOutageTracker(clock = { now })
        val client = clientOf(
            CloudflareInterceptor(ChallengePass(ChallengeBrowsers(NoCookies, NoBrowser), outages::isChallenged), outages),
            answering { sent ->
                cannedResponse(sent, code = 403).newBuilder().header("Server", "cloudflare").header("cf-mitigated", "challenge").build()
            },
        )
        val call = { url: String -> client.newCall(Request.Builder().url(url).header("User-Agent", "agent/1").build()).execute() }

        val failure = assertFailsWith<HostUnavailableException> { call("https://www.site.test/list") }
        now += 1_000L
        assertFailsWith<HostUnavailableException> { call("https://site.test/other") }

        assertEquals(HostOutageKind.CHALLENGE, failure.kind)
        val outage = outages.outages.value.getValue("site.test")
        assertEquals(RefusedRequest("https://www.site.test/list", "agent/1"), outage.refused)
        // The second challenge did not renew the record, so it still expires when the first one's does.
        assertEquals(HostOutageTracker.DEFAULT_OUTAGE_MS, outage.untilEpochMs)
    }

    @Test
    fun `a challenge another client recorded without the refused request gains the extension's, keeping its expiry`() {
        var now = 0L
        val outages = HostOutageTracker(clock = { now })
        outages.record("https://site.test/", HostOutageKind.CHALLENGE)
        now += 1_000L
        val client = clientOf(
            CloudflareInterceptor(ChallengePass(ChallengeBrowsers(NoCookies, NoBrowser), outages::isChallenged), outages),
            answering { sent ->
                cannedResponse(sent, code = 403).newBuilder().header("cf-mitigated", "challenge").build()
            },
        )

        assertFailsWith<HostUnavailableException> {
            client.newCall(Request.Builder().url("https://site.test/manga").header("User-Agent", "agent/1").build()).execute()
        }

        val outage = outages.outages.value.getValue("site.test")
        assertEquals(RefusedRequest("https://site.test/manga", "agent/1"), outage.refused)
        assertEquals(HostOutageTracker.DEFAULT_OUTAGE_MS, outage.untilEpochMs)
    }

    @Test
    fun `a challenged host that answers successfully again stops needing verification, and only that host`() {
        val outages = HostOutageTracker()
        outages.record("https://api.site.test/list", HostOutageKind.CHALLENGE)
        outages.record("https://other.test/", HostOutageKind.CHALLENGE)
        outages.record("https://down.test/", HostOutageKind.MAINTENANCE)
        val client = clientOf(
            CloudflareInterceptor(ChallengePass(ChallengeBrowsers(NoCookies, NoBrowser), outages::isChallenged), outages),
            answering { sent -> cannedResponse(sent, code = if (sent.url.host == "other.test") 404 else 200) },
        )

        listOf("https://api.site.test/list", "https://other.test/", "https://down.test/").forEach { url ->
            client.newCall(Request.Builder().url(url).build()).execute().close()
        }

        assertEquals(setOf("other.test", "down.test"), outages.outages.value.keys)
    }

    @Test
    fun `a refusal without a challenge reaches the extension and is logged once per host, without the server's text`() {
        val logged = mutableListOf<String>()
        Diagnostics.install { _, message, _ -> logged += message }
        val outages = HostOutageTracker()
        val client = clientOf(
            CloudflareInterceptor(ChallengePass(ChallengeBrowsers(NoCookies, NoBrowser), outages::isChallenged), outages),
            answering { sent ->
                val server = if (sent.url.host == "site.test") "cloudflare" else "edge /chapter/private-title?sig=x"
                cannedResponse(sent, code = 403).newBuilder().header("Server", server).build()
            },
        )
        fun fetch(url: String, cookie: String? = null) =
            client.newCall(Request.Builder().url(url).apply { cookie?.let { header("Cookie", it) } }.build()).execute().use { it.code }

        assertEquals(403, fetch("https://site.test/chapter/1", cookie = "cf_clearance=old"))
        fetch("https://site.test/chapter/2")
        fetch("https://other.test/page")
        assertEquals(
            listOf(
                "site.test refused 403 with a clearance, no challenge, from Cloudflare",
                "other.test refused 403 without a clearance, no challenge, from another server",
            ),
            logged,
        )
    }

    @Test
    fun `an IOException passes through as it is`() {
        val client = clientOf(UncaughtExceptionInterceptor(), Interceptor { throw IOException("offline") })

        val failure = assertFailsWith<IOException> { client.newCall(request).execute() }

        assertEquals("offline", failure.message)
        assertNull(failure.cause)
    }

    @Test
    fun `the default agent is added when the request names none`() {
        assertEquals("test-agent", agentSeenFor(request))
    }

    @Test
    fun `the agent a request names is kept`() {
        assertEquals("custom/1", agentSeenFor(request.newBuilder().header("User-Agent", "custom/1").build()))
    }

    private fun agentSeenFor(request: Request): String? {
        var seen: String? = null
        val client = clientOf(
            UserAgentInterceptor { "test-agent" },
            answering { sent ->
                seen = sent.header("User-Agent")
                cannedResponse(sent)
            },
        )
        client.newCall(request).execute().close()
        return seen
    }

    private object NoCookies : BrowserCookieStore {
        override fun cookieHeader(url: String): String? = null

        override fun setCookie(url: String, setCookie: String) = Unit

        override fun flush() = Unit

        override fun expire(url: String, name: String?) = Unit
    }

    private object NoBrowser : ChallengeBrowser {
        override fun mayWait() = true

        override fun pass(request: Request, passed: () -> Boolean, cancelled: () -> Boolean) = false
    }

    private fun clientOf(vararg interceptors: Interceptor) = OkHttpClient.Builder().apply { interceptors.forEach(::addInterceptor) }.build()
}
