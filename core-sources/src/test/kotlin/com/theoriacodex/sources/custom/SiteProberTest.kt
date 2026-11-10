package com.theoriacodex.sources.custom

import com.theoriacodex.domain.source.ProbeFailure
import com.theoriacodex.domain.source.ProtocolFamily
import com.theoriacodex.domain.source.SiteAddress
import com.theoriacodex.domain.source.SiteAddressError
import com.theoriacodex.domain.source.SiteAddressParse
import com.theoriacodex.domain.source.SiteCredentials
import com.theoriacodex.domain.source.SiteProbeResult
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.http.SourceHttpResponse
import java.net.UnknownHostException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Probe outcomes against the captured fixtures, through the real redirect policy. */
class SiteProberTest {
    private val transport = FixtureSiteTransport(*FIXTURE_SITES)
    private val http = CustomSiteHttpClient(10_000, 10_000, 3, transport = transport::execute)

    /** Built-in hosts are disabled so shipped sites' fixtures exercise the families as if user-added. */
    private val prober = SiteProber(SiteFamilyRegistry.default(), http, builtInHosts = emptyMap())

    @Test
    fun `AIBooru is Danbooru with its measured guest term limit`() = runTest {
        assertEquals(supported("https://aibooru.online", ProtocolFamily.DANBOORU, "aibooru.online", termLimit = 4), prober.probe("aibooru.online"))
        assertEquals(
            listOf(
                "https://aibooru.online/counts/posts.json",
                "https://aibooru.online/profile.json",
                "https://aibooru.online/posts.json?limit=40&page=1&tags=",
                // The home page and touch icon, for the name and icon; with no fixtures the host stays the label.
                "https://aibooru.online/",
                "https://aibooru.online/apple-touch-icon.png",
            ),
            transport.requested,
        )
    }

    @Test
    fun `Safebooru is Gelbooru 0_2 from its XML envelope and JSON list`() = runTest {
        val result = prober.probe("https://safebooru.org/index.php?page=post&s=list&tags=sky")

        assertEquals(supported("https://safebooru.org", ProtocolFamily.GELBOORU_02, "safebooru.org", termLimit = null), result)
        assertEquals(5, transport.requested.size)
    }

    @Test
    fun `an envelope the family adapter cannot read is not locked in`() = runTest {
        val envelopeOnly = object : SourceHttpClient by NoWrites {
            override suspend fun get(url: String, query: Map<String, String>, headers: Map<String, String>): SourceHttpResponse =
                when {
                    query["page"] == "dapi" && "json" !in query -> SourceHttpResponse(200, """<posts count="3" offset="0"></posts>""")
                    query["page"] == "dapi" -> SourceHttpResponse(200, "<!doctype html><p>not json</p>")
                    else -> SourceHttpResponse(404, "")
                }
        }

        val result = SiteProber(SiteFamilyRegistry.default(), envelopeOnly).probe("booru.example")

        assertEquals(SiteProbeResult.NotABooru(address("https://booru.example")), result)
    }

    @Test
    fun `Gelbooru's empty 401 and rule34's missing-authentication reply ask for keys`() = runTest {
        listOf("https://gelbooru.com", "https://api.rule34.xxx").forEach { url ->
            assertEquals(SiteProbeResult.CredentialsRequired(address(url), ProtocolFamily.GELBOORU_02, rejected = false), prober.probe(url))
        }
    }

    @Test
    fun `keys that are refused again report a rejected credential`() = runTest {
        val result = prober.probe("gelbooru.com", credentials = SiteCredentials("1", "invalid"))

        assertEquals(SiteProbeResult.CredentialsRequired(address("https://gelbooru.com"), ProtocolFamily.GELBOORU_02, rejected = true), result)
        assertTrue(transport.requested.any { "user_id=1" in it && "api_key=invalid" in it })
    }

    @Test
    fun `a working key gets through where anonymous access is refused with a page`() = runTest {
        val site = danbooruSite(anonymous = SourceHttpResponse(403, "<!DOCTYPE html><html><title>Access denied</title></html>"))

        val result = SiteProber(SiteFamilyRegistry.default(), site).probe("booru.example", GOOD_KEYS) as SiteProbeResult.Supported

        assertEquals(ProtocolFamily.DANBOORU, result.family)
        assertTrue("the site can't work without its keys", result.credentialsRequired)
    }

    @Test
    fun `a bad key is reported even on a site open to everyone`() = runTest {
        val site = danbooruSite(anonymous = null)

        val result = SiteProber(SiteFamilyRegistry.default(), site).probe("booru.example", SiteCredentials("name", "wrong"))

        assertEquals(SiteProbeResult.CredentialsRequired(address("https://booru.example"), ProtocolFamily.DANBOORU, rejected = true), result)
    }

    @Test
    fun `a good key on an open site leaves the site usable without keys`() = runTest {
        val result = SiteProber(SiteFamilyRegistry.default(), danbooruSite(anonymous = null)).probe("booru.example", GOOD_KEYS)

        assertEquals(false, (result as SiteProbeResult.Supported).credentialsRequired)
    }

    @Test
    fun `a saved site is checked with its keys alone`() = runTest {
        val site = danbooruSite(anonymous = SourceHttpResponse(403, "<!DOCTYPE html><html></html>"))

        val result = SiteProber(SiteFamilyRegistry.default(), site)
            .probe("booru.example", GOOD_KEYS, knownFamily = ProtocolFamily.DANBOORU)

        assertTrue(result is SiteProbeResult.Supported)
        assertEquals(
            "only the home page and its icon go out without keys",
            setOf("https://booru.example/", "https://booru.example/apple-touch-icon.png"),
            site.requests.filterNot { (_, query) -> query["api_key"] == GOOD_KEYS.apiKey }.mapTo(mutableSetOf()) { (url, _) -> url },
        )
    }

    @Test
    fun `Sakugabooru is recognized as unsupported Moebooru, including through the apex redirect`() = runTest {
        listOf("www.sakugabooru.com", "sakugabooru.com").forEach { input ->
            val address = (SiteAddress.parse(input) as SiteAddressParse.Valid).address
            assertEquals(SiteProbeResult.UnsupportedFamily(address, "Moebooru"), prober.probe(input))
        }
    }

    @Test
    fun `an ordinary website is not a booru`() = runTest {
        assertEquals(SiteProbeResult.NotABooru(address("https://example.com")), prober.probe("example.com"))
    }

    @Test
    fun `a maintenance page makes the probe inconclusive`() = runTest {
        assertEquals(SiteProbeResult.Unreachable(address("https://danbooru.donmai.us"), ProbeFailure.BLOCKED), prober.probe("danbooru.donmai.us"))
    }

    @Test
    fun `a browser verification gate on every route makes the probe inconclusive`() = runTest {
        val result = prober.probe("booru.challenge-gate.example")

        assertEquals(SiteProbeResult.Unreachable(address("https://booru.challenge-gate.example"), ProbeFailure.BLOCKED), result)
        // Asking the site itself (counts, DAPI) and the unsupported-family signature, nothing else.
        assertEquals(3, transport.requested.size)
    }

    @Test
    fun `shipped sites and invalid input never reach the network`() = runTest {
        val shipped = SiteProber(SiteFamilyRegistry.default(), http)

        assertEquals(SiteProbeResult.AlreadyBuiltIn("DANBOORU"), shipped.probe("https://danbooru.donmai.us/posts?tags=x"))
        assertEquals(SiteProbeResult.AlreadyBuiltIn("AIBOORU"), shipped.probe("aibooru.online"))
        assertEquals(SiteProbeResult.AlreadyBuiltIn("GELBOORU"), shipped.probe("www.gelbooru.com"))
        assertEquals(SiteProbeResult.AlreadyBuiltIn("RULE34XXX"), shipped.probe("rule34.xxx"))
        assertEquals(SiteProbeResult.AlreadyBuiltIn("RULE34XXX"), shipped.probe("api.rule34.xxx"))
        assertEquals(SiteProbeResult.InvalidAddress(SiteAddressError.NOT_HTTPS), shipped.probe("http://safebooru.org"))
        assertEquals(emptyList<String>(), transport.requested)
    }

    @Test
    fun `transport failures are inconclusive, not NotABooru`() = runTest {
        val failing = object : SourceHttpClient by NoWrites {
            override suspend fun get(url: String, query: Map<String, String>, headers: Map<String, String>): SourceHttpResponse =
                throw UnknownHostException(url)
        }

        val result = SiteProber(SiteFamilyRegistry.default(), failing).probe("booru.example")

        assertEquals(SiteProbeResult.Unreachable(address("https://booru.example"), ProbeFailure.DNS), result)
    }

    @Test
    fun `the overall deadline ends a hanging probe`() = runTest {
        val hanging = object : SourceHttpClient by NoWrites {
            override suspend fun get(url: String, query: Map<String, String>, headers: Map<String, String>): SourceHttpResponse =
                awaitCancellation()
        }

        val result = SiteProber(SiteFamilyRegistry.default(), hanging, deadlineMs = 1_000).probe("booru.example")

        assertEquals(SiteProbeResult.Unreachable(address("https://booru.example"), ProbeFailure.TIMEOUT), result)
    }

    @Test
    fun `a probe never sends more than its request budget`() = runTest {
        var sent = 0
        val counting = object : SourceHttpClient by NoWrites {
            override suspend fun get(url: String, query: Map<String, String>, headers: Map<String, String>): SourceHttpResponse {
                sent += 1
                return SourceHttpResponse(404, "")
            }
        }

        SiteProber(SiteFamilyRegistry.default(), counting, maxRequests = 2).probe("booru.example")

        assertEquals(2, sent)
    }

    @Test
    fun `site name comes from og site_name, else the title before its separator`() {
        assertEquals("Moebooru Fans", siteNameFromHtml("""<meta property="og:site_name" content="Moebooru Fans"><title>x | y</title>"""))
        assertEquals("Gelbooru", siteNameFromHtml("<title>Gelbooru | Anime and Hentai Imageboard</title>"))
        assertEquals(null, siteNameFromHtml("<title>  </title>"))
    }

    @Test
    fun `site icon is the largest declared raster icon, resolved against the page`() {
        val html = """
            <link rel="icon" href="/favicon.ico">
            <link rel="icon" type="image/svg+xml" href="/logo.svg">
            <link rel="apple-touch-icon" href="/touch.png">
            <link rel="icon" sizes="32x32" href="https://cdn.example/32.png">
        """
        assertEquals("https://booru.example/touch.png", parseHomePage(html, "https://booru.example/").iconUrl)
        assertEquals(null, parseHomePage("<title>x</title>", "https://booru.example/").iconUrl)
    }

    private fun supported(url: String, family: ProtocolFamily, label: String, termLimit: Int?) =
        SiteProbeResult.Supported(address(url), family, label, termLimit, credentialsRequired = false)

    private fun address(url: String): SiteAddress = (SiteAddress.parse(url) as SiteAddressParse.Valid).address

    /** A Danbooru site that accepts [GOOD_KEYS] and answers its API with [anonymous] when no key is sent; null when it is open. */
    private fun danbooruSite(anonymous: SourceHttpResponse?) = object : SourceHttpClient by NoWrites {
        val requests = mutableListOf<Pair<String, Map<String, String>>>()

        override suspend fun get(url: String, query: Map<String, String>, headers: Map<String, String>): SourceHttpResponse {
            requests += url to query
            val key = query["api_key"]
            return when {
                key != null && key != GOOD_KEYS.apiKey ->
                    SourceHttpResponse(401, """{"success":false,"error":"SessionLoader::AuthenticationFailure","message":"Invalid API key"}""")
                key == null && anonymous != null && url.endsWith(".json") -> anonymous
                url.endsWith("/counts/posts.json") -> SourceHttpResponse(200, """{"counts":{"posts":3}}""")
                url.endsWith("/profile.json") -> SourceHttpResponse(200, """{"tag_query_limit":4}""")
                url.endsWith("/posts.json") -> SourceHttpResponse(200, "[]")
                else -> SourceHttpResponse(404, "")
            }
        }
    }

    private object NoWrites : SourceHttpClient {
        override suspend fun get(url: String, query: Map<String, String>, headers: Map<String, String>): SourceHttpResponse =
            error("unused")

        override suspend fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): SourceHttpResponse =
            error("Probes never write")
    }

    companion object {
        private val GOOD_KEYS = SiteCredentials("name", "good-key")
        val FIXTURE_SITES = arrayOf("danbooru", "aibooru", "safebooru", "gelbooru", "rule34xxx", "sakugabooru", "sakugabooru_apex", "challenge_gate", "example")
    }
}
