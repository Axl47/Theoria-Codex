package com.theoriacodex.sources.custom

import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.source.ProbeFailure
import com.theoriacodex.domain.source.ProtocolFamily
import com.theoriacodex.domain.source.SiteAddress
import com.theoriacodex.domain.source.SiteAddressError
import com.theoriacodex.domain.source.SiteAddressParse
import com.theoriacodex.domain.source.SiteCredentials
import com.theoriacodex.domain.source.SiteProbeResult
import com.theoriacodex.domain.source.SiteProfile
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.http.SourceHttpResponse
import java.io.IOException
import java.net.UnknownHostException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteProberTest {
    private val transport = FixtureSiteTransport(*FIXTURE_SITES)
    private val http = CustomSiteHttpClient(3, transport = transport::execute)

    private val prober = SiteProber(SiteFamilyRegistry.default(), http, builtInHosts = emptyMap())

    @Test
    fun `AIBooru is Danbooru with its measured guest term limit (ADR 0040)`() = runTest {
        assertEquals(supported("https://aibooru.online", ProtocolFamily.DANBOORU, "aibooru.online", termLimit = 4), prober.probe("aibooru.online"))
        assertEquals(
            listOf(
                "https://aibooru.online/counts/posts.json",
                "https://aibooru.online/profile.json",
                "https://aibooru.online/posts.json?limit=40&page=1&tags=",
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
    fun `an envelope the family adapter cannot read is not locked in (ADR 0040)`() = runTest {
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
    fun `Gelbooru's empty 401 and rule34's missing-authentication reply ask for keys (ADR 0040)`() = runTest {
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
    fun `a working key gets through where anonymous access is refused with a page (ADR 0040)`() = runTest {
        val site = danbooruSite(anonymous = SourceHttpResponse(403, "<!DOCTYPE html><html><title>Access denied</title></html>"))

        val result = SiteProber(SiteFamilyRegistry.default(), site).probe("booru.example", GOOD_KEYS) as SiteProbeResult.Supported

        assertEquals(ProtocolFamily.DANBOORU, result.family)
        assertTrue("the site can't work without its keys", result.credentialsRequired)
    }

    @Test
    fun `a bad key is reported even on a site open to everyone (ADR 0040)`() = runTest {
        val site = danbooruSite(anonymous = null)

        val result = SiteProber(SiteFamilyRegistry.default(), site).probe("booru.example", SiteCredentials("name", "wrong"))

        assertEquals(SiteProbeResult.CredentialsRequired(address("https://booru.example"), ProtocolFamily.DANBOORU, rejected = true), result)
    }

    @Test
    fun `a good key on an open site leaves the site usable without keys (ADR 0040)`() = runTest {
        val result = SiteProber(SiteFamilyRegistry.default(), danbooruSite(anonymous = null)).probe("booru.example", GOOD_KEYS)

        result as SiteProbeResult.Supported
        assertEquals(false, result.credentialsRequired)
        assertEquals("the guest limit, which still holds without the key", 4, result.searchTermLimit)
    }

    @Test
    fun `a saved site is checked with its keys alone (ADR 0040)`() = runTest {
        val site = danbooruSite(anonymous = SourceHttpResponse(403, "<!DOCTYPE html><html></html>"))

        val result = SiteProber(SiteFamilyRegistry.default(), site)
            .probe("booru.example", GOOD_KEYS, knownFamily = ProtocolFamily.DANBOORU)

        assertTrue(result is SiteProbeResult.Supported)
        assertEquals(
            "only the home page and its icon go out without keys",
            setOf("https://booru.example/", "https://booru.example/apple-touch-icon.png"),
            site.requests.filterNot { (_, query) -> query["api_key"] == GOOD_KEYS.apiKey }.mapTo(mutableSetOf()) { (url, _) -> url },
        )

        val tried = mutableListOf<ProtocolFamily>()
        val families = listOf(ProtocolFamily.DANBOORU, ProtocolFamily.GELBOORU_02).map { protocol -> family(protocol) { _, _ -> tried += protocol } }
        SiteProber(SiteFamilyRegistry(families, emptyList()), withHomePage { SourceHttpResponse(404, "") })
            .probe("booru.example", GOOD_KEYS, knownFamily = ProtocolFamily.GELBOORU_02)
        assertEquals(listOf(ProtocolFamily.GELBOORU_02, ProtocolFamily.DANBOORU), tried)
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
    fun `a maintenance page makes the probe inconclusive (ADR 0040)`() = runTest {
        assertEquals(SiteProbeResult.Unreachable(address("https://danbooru.donmai.us"), ProbeFailure.BLOCKED), prober.probe("danbooru.donmai.us"))
    }

    @Test
    fun `a browser verification gate on every route makes the probe inconclusive`() = runTest {
        val result = prober.probe("booru.challenge-gate.example")

        assertEquals(SiteProbeResult.Unreachable(address("https://booru.challenge-gate.example"), ProbeFailure.BLOCKED), result)
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
    fun `the overall deadline ends a hanging probe (ADR 0040)`() = runTest {
        val hanging = object : SourceHttpClient by NoWrites {
            override suspend fun get(url: String, query: Map<String, String>, headers: Map<String, String>): SourceHttpResponse =
                awaitCancellation()
        }

        val result = SiteProber(SiteFamilyRegistry.default(), hanging, deadlineMs = 1_000).probe("booru.example")

        assertEquals(SiteProbeResult.Unreachable(address("https://booru.example"), ProbeFailure.TIMEOUT), result)
    }

    @Test
    fun `a probe never sends more than its request budget (ADR 0040)`() = runTest {
        var sent = 0
        val counting = object : SourceHttpClient by NoWrites {
            override suspend fun get(url: String, query: Map<String, String>, headers: Map<String, String>): SourceHttpResponse {
                sent += 1
                return SourceHttpResponse(404, "")
            }
        }

        SiteProber(SiteFamilyRegistry.default(), counting, maxRequests = 2).probe("booru.example")
        assertEquals(2, sent)

        sent = 0
        val greedy = family(ProtocolFamily.DANBOORU) { address, context -> repeat(20) { runCatching { context.get(address, "/$it") } } }
        SiteProber(SiteFamilyRegistry(listOf(greedy), emptyList()), counting).probe("booru.example")
        assertEquals("the default budget", 8, sent)
    }

    @Test
    fun `a keyed Danbooru match with its anonymous repeat spends exactly the default request budget (ADR 0040)`() = runTest {
        val site = danbooruSite(anonymous = null)

        val result = SiteProber(SiteFamilyRegistry.default(), site).probe("booru.example", GOOD_KEYS)

        assertTrue(result is SiteProbeResult.Supported)
        assertEquals(8, SiteProber.MAX_REQUESTS)
        assertEquals(8, site.requests.size)
        assertEquals("https://booru.example/apple-touch-icon.png", site.requests.last().first)
    }

    @Test
    fun `a failed home page leaves the host as label and the touch icon is used only when it answers 200 with an image (ADR 0040)`() = runTest {
        val site = withHomePage { url -> if (url.endsWith("/apple-touch-icon.png")) TOUCH_ICON else throw IOException("home page down") }

        val result = SiteProber(SiteFamilyRegistry.default(), site).probe("booru.example") as SiteProbeResult.Supported
        val page = SourceHttpResponse(200, "<html></html>", mapOf("Content-Type" to listOf("text/html")))
        val htmlAtIconPath = withHomePage { url -> if (url.endsWith("/apple-touch-icon.png")) page else throw IOException("home page down") }

        val missingIcon = SourceHttpResponse(404, "", mapOf("Content-Type" to listOf("image/png")))
        val errorPages = withHomePage { url ->
            if (url.endsWith("/apple-touch-icon.png")) missingIcon else SourceHttpResponse(500, "<title>Server Error</title>")
        }
        val errorResult = SiteProber(SiteFamilyRegistry.default(), errorPages).probe("booru.example") as SiteProbeResult.Supported

        assertEquals("booru.example", result.suggestedLabel)
        assertEquals("https://booru.example/apple-touch-icon.png", result.iconUrl)
        assertEquals(null, (SiteProber(SiteFamilyRegistry.default(), htmlAtIconPath).probe("booru.example") as SiteProbeResult.Supported).iconUrl)
        assertEquals("booru.example", errorResult.suggestedLabel)
        assertEquals(null, errorResult.iconUrl)
    }

    @Test
    fun `the touch icon replaces a sizeless or under 64 px declared icon and leaves one of 64 px (ADR 0040)`() = runTest {
        suspend fun iconFor(size: Int?): String? {
            val sizes = size?.let { """ sizes="${it}x$it"""" }.orEmpty()
            val page = """<title>Booru</title><link rel="icon"$sizes href="/declared.png">"""
            val site = withHomePage { url -> if (url.endsWith("/apple-touch-icon.png")) TOUCH_ICON else SourceHttpResponse(200, page) }
            return (SiteProber(SiteFamilyRegistry.default(), site).probe("booru.example") as SiteProbeResult.Supported).iconUrl
        }

        assertEquals("https://booru.example/apple-touch-icon.png", iconFor(63))
        assertEquals("https://booru.example/declared.png", iconFor(64))
        assertEquals("a sizeless icon counts as 16 px", "https://booru.example/apple-touch-icon.png", iconFor(null))
    }

    @Test
    fun `a declared icon must be HTTPS and not SVG by extension or type (ADR 0040)`() {
        val html = """
            <link rel="icon" sizes="512x512" href="http://booru.example/cleartext.png">
            <link rel="icon" sizes="256x256" type="image/svg+xml" href="/typed.png">
            <link rel="icon" sizes="128x128" href="/vector.SVG?v=2">
            <link rel="icon" sizes="16x16" href="/raster.png">
        """

        assertEquals("https://booru.example/raster.png", parseHomePage(html, "https://booru.example/").iconUrl)
    }

    @Test
    fun `a Danbooru site that does not report its tag limit falls back to two (ADR 0041)`() = runTest {
        val silent = withHomePage(profile = SourceHttpResponse(200, "{}")) { SourceHttpResponse(404, "") }
        val missing = withHomePage(profile = SourceHttpResponse(404, "")) { SourceHttpResponse(404, "") }
        val unreachable = withHomePage(profile = null) { SourceHttpResponse(404, "") }

        for (site in listOf(silent, missing, unreachable)) {
            val result = SiteProber(SiteFamilyRegistry.default(), site).probe("booru.example") as SiteProbeResult.Supported
            assertEquals(2, result.searchTermLimit)
        }
    }

    @Test
    fun `site name comes from og site_name, else the title before its separator`() {
        assertEquals("Moebooru Fans", siteNameFromHtml("""<meta property="og:site_name" content="Moebooru Fans"><title>x | y</title>"""))
        assertEquals("Gelbooru", siteNameFromHtml("<title>Gelbooru | Anime and Hentai Imageboard</title>"))
        assertEquals(null, siteNameFromHtml("<title>  </title>"))
    }

    @Test
    fun `site icon is the largest declared raster icon, resolved against the page (ADR 0040)`() {
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
                url.endsWith("/profile.json") -> SourceHttpResponse(200, """{"tag_query_limit":${if (key == null) 4 else 12}}""")
                url.endsWith("/posts.json") -> SourceHttpResponse(200, "[]")
                else -> SourceHttpResponse(404, "")
            }
        }
    }

    private fun withHomePage(
        profile: SourceHttpResponse? = SourceHttpResponse(200, """{"tag_query_limit":4}"""),
        home: (String) -> SourceHttpResponse,
    ) = object : SourceHttpClient by NoWrites {
        override suspend fun get(url: String, query: Map<String, String>, headers: Map<String, String>): SourceHttpResponse =
            when {
                url.endsWith("/counts/posts.json") -> SourceHttpResponse(200, """{"counts":{"posts":3}}""")
                url.endsWith("/profile.json") -> profile ?: throw IOException("profile unreachable")
                url.endsWith("/posts.json") -> SourceHttpResponse(200, "[]")
                else -> home(url)
            }
    }

    private fun family(protocol: ProtocolFamily, probing: suspend (SiteAddress, SiteRequestContext) -> Unit) = object : SiteFamily {
        override val family = protocol

        override suspend fun probe(address: SiteAddress, context: SiteRequestContext): FamilyProbe {
            probing(address, context)
            return FamilyProbe.NoMatch
        }

        override fun createAdapter(profile: SiteProfile, source: SourceKey, context: SiteRequestContext) = error("unused")
    }

    private object NoWrites : SourceHttpClient {
        override suspend fun get(url: String, query: Map<String, String>, headers: Map<String, String>): SourceHttpResponse =
            error("unused")

        override suspend fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): SourceHttpResponse =
            error("Probes never write")
    }

    companion object {
        private val GOOD_KEYS = SiteCredentials("name", "good-key")
        private val TOUCH_ICON = SourceHttpResponse(200, "", mapOf("Content-Type" to listOf("image/vnd.microsoft.icon")))
        val FIXTURE_SITES = arrayOf("danbooru", "aibooru", "safebooru", "gelbooru", "rule34xxx", "sakugabooru", "sakugabooru_apex", "challenge_gate", "example")
    }
}
