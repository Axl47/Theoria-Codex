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
    private val http = CustomSiteHttpClient(10_000, 10_000, 3, transport::execute)

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
            ),
            transport.requested,
        )
    }

    @Test
    fun `Safebooru is Gelbooru 0_2 from its XML envelope and JSON list`() = runTest {
        val result = prober.probe("https://safebooru.org/index.php?page=post&s=list&tags=sky")

        assertEquals(supported("https://safebooru.org", ProtocolFamily.GELBOORU_02, "safebooru.org", termLimit = null), result)
        assertEquals(3, transport.requested.size)
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
        // Danbooru answered with a plain 404, so the keys never went to it.
        assertTrue(transport.requested.none { "/counts/posts.json" in it && "api_key" in it })
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

    private fun supported(url: String, family: ProtocolFamily, label: String, termLimit: Int?) =
        SiteProbeResult.Supported(address(url), family, label, termLimit, credentialsRequired = false)

    private fun address(url: String): SiteAddress = (SiteAddress.parse(url) as SiteAddressParse.Valid).address

    private object NoWrites : SourceHttpClient {
        override suspend fun get(url: String, query: Map<String, String>, headers: Map<String, String>): SourceHttpResponse =
            error("unused")

        override suspend fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): SourceHttpResponse =
            error("Probes never write")
    }

    companion object {
        val FIXTURE_SITES = arrayOf("danbooru", "aibooru", "safebooru", "gelbooru", "rule34xxx", "sakugabooru", "sakugabooru_apex", "example")
    }
}
