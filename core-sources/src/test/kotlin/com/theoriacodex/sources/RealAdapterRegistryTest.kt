package com.theoriacodex.sources

import com.theoriacodex.domain.adapter.AccountProfileSourceAdapter
import com.theoriacodex.domain.adapter.FileHashLookupSourceAdapter
import com.theoriacodex.domain.adapter.SearchMetadataSourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.source.ProtocolFamily
import com.theoriacodex.domain.source.SiteAddress
import com.theoriacodex.domain.source.SiteAddressParse
import com.theoriacodex.domain.source.SiteProfile
import com.theoriacodex.sources.credentials.PixivAuthTokens
import com.theoriacodex.sources.credentials.UserApiKey
import com.theoriacodex.sources.custom.SiteFamilyRegistry
import com.theoriacodex.sources.custom.SiteRequestContext
import com.theoriacodex.sources.hitomi.HitomiSourceAdapter
import com.theoriacodex.sources.http.SourceByteRange
import com.theoriacodex.sources.http.SourceByteResponse
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.http.SourceHttpResponse
import com.theoriacodex.sources.testing.FakeCredentialsProvider
import com.theoriacodex.sources.testing.FakeHttpClient
import com.theoriacodex.sources.testing.commentTestPost
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RealAdapterRegistryTest {
    @Test
    fun `default exposure only includes pixiv`() {
        val registry = RealAdapterRegistry(
            credentialsProvider = FakeCredentialsProvider(),
            httpClient = FakeHttpClient(),
        )

        assertEquals(setOf(SourceKey.PIXIV), registry.availableSources())
        assertNull(registry.adapterFor(SourceKey.AIBOORU))
        assertNull(registry.adapterFor(SourceKey.GELBOORU))
        assertNull(registry.adapterFor(SourceKey.HITOMI))
        assertNull(registry.adapterFor(SourceKey.IWARA))
        assertNull(registry.adapterFor(SourceKey.NHENTAI))
        assertNull(registry.adapterFor(SourceKey.RULE34PAHEAL))
    }

    @Test
    fun `registry can expose additional sources for later phases`() {
        val registry = RealAdapterRegistry(
            credentialsProvider = FakeCredentialsProvider(),
            httpClient = FakeHttpClient(),
            exposedSources = setOf(
                SourceKey.PIXIV,
                SourceKey.AIBOORU,
                SourceKey.NHENTAI,
                SourceKey.HITOMI,
                SourceKey.IWARA,
                SourceKey.RULE34PAHEAL,
                SourceKey.RULE34VIDEO,
                SourceKey.RULE34GEN,
            ),
        )

        assertTrue(SourceKey.PIXIV in registry.availableSources())
        assertTrue(SourceKey.AIBOORU in registry.availableSources())
        assertTrue(SourceKey.NHENTAI in registry.availableSources())
        assertTrue(SourceKey.HITOMI in registry.availableSources())
        assertTrue(registry.adapterFor(SourceKey.HITOMI) is HitomiSourceAdapter)
        assertTrue(SourceKey.IWARA in registry.availableSources())
        assertTrue(SourceKey.RULE34PAHEAL in registry.availableSources())
        assertTrue(SourceKey.RULE34VIDEO in registry.availableSources())
        assertTrue(SourceKey.RULE34GEN in registry.availableSources())
    }

    @Test
    fun `only the families with an md5 search look up files by hash (ADR 0008, 0053)`() {
        val http = FakeHttpClient()
        val registry = RealAdapterRegistry(FakeCredentialsProvider(), http, exposedSources = SourceKey.builtIns.toSet())
        val customFamilies = customFamilyAdapters(http)

        assertEquals(SourceKey.builtIns.toSet(), registry.availableSources())
        assertEquals(
            setOf(SourceKey.AIBOORU, SourceKey.DANBOORU, SourceKey.GELBOORU, SourceKey.RULE34XXX),
            SourceKey.builtIns.filter { registry.adapterFor(it) is FileHashLookupSourceAdapter }.toSet(),
        )
        assertEquals(setOf(ProtocolFamily.DANBOORU, ProtocolFamily.GELBOORU_02), customFamilies.filterValues { it is FileHashLookupSourceAdapter }.keys)
    }

    @Test
    fun `every source fails a lookup on a failed request instead of answering null (ADR 0008)`() = runTest {
        val unavailable = FailingTransport { SourceByteResponse(503, ByteArray(0)) }
        val rateLimited = FailingTransport { SourceByteResponse(429, ByteArray(0)) }
        val unreachable = IOException("transport down")
        val down = FailingTransport { throw unreachable }

        val unavailableReport = failedRequestReport(unavailable) { failure -> failure is SourceAdapterException }
        val rateLimitedReport = failedRequestReport(rateLimited) { failure -> failure is SourceAdapterException }
        val downReport = failedRequestReport(down) { failure ->
            failure is SourceAdapterException && generateSequence(failure.cause) { it.cause }.any { it === unreachable }
        }

        assertEquals(emptyList<String>(), unavailableReport.wrong)
        assertEquals(emptyList<String>(), rateLimitedReport.wrong)
        assertEquals(emptyList<String>(), downReport.wrong)
        val everySource = SourceKey.builtIns.map(SourceKey::id).toSet() + "custom_booru_example"
        for (report in listOf(unavailableReport, rateLimitedReport, downReport)) {
            assertEquals("every source has a lookup checked", everySource, report.checkedSources)
            assertTrue("a lookup per source at least", report.checked >= everySource.size)
        }
    }

    private class FailedRequestReport(val checked: Int, val wrong: List<String>, val checkedSources: Set<String>)

    private suspend fun failedRequestReport(http: FailingTransport, isProviderFailure: (Throwable?) -> Boolean): FailedRequestReport {
        val credentials = FakeCredentialsProvider().apply {
            pixivTokens = PixivAuthTokens("access", "refresh", Long.MAX_VALUE)
            gelbooruCredentials = UserApiKey("1", "key")
            rule34XxxCredentials = UserApiKey("1", "key")
        }
        val registry = RealAdapterRegistry(credentials, http, exposedSources = SourceKey.builtIns.toSet())
        assertEquals(SourceKey.builtIns.toSet(), registry.availableSources())
        val adapters = SourceKey.builtIns.map { key -> requireNotNull(registry.adapterFor(key)) } + customFamilyAdapters(http).values
        var checked = 0
        val wrong = mutableListOf<String>()
        val checkedSources = mutableSetOf<String>()
        for (adapter in adapters) {
            val post = commentTestPost(adapter.sourceKey, "123")
            val creator = CreatorProfile(adapter.sourceKey, "alice", "1", "https://www.iwara.tv/profile/alice", "user:alice")
            val lookups = listOfNotNull(
                "resolvePost" to suspend { adapter.resolvePost(post.id) },
                (adapter as? SearchMetadataSourceAdapter)?.let { "resolveSearchMetadata" to suspend { it.resolveSearchMetadata(post) } },
                (adapter as? FileHashLookupSourceAdapter)?.let { "findByFileHash" to suspend { it.findByFileHash("0123456789abcdef0123456789abcdef") } },
                (adapter as? AccountProfileSourceAdapter)?.let { "accountDetails" to suspend { it.accountDetails(creator) } },
            )
            for ((name, lookup) in lookups) {
                val before = http.requests
                val failure = runCatching { lookup() }.exceptionOrNull()
                val asked = http.requests > before
                val where = "${adapter.sourceKey.id}.$name"
                if (name == "accountDetails" && !asked && failure == null) continue
                checked += 1
                checkedSources += adapter.sourceKey.id
                if (!asked) wrong += "$where never asked the site"
                if (!isProviderFailure(failure)) wrong += "$where ended with $failure"
            }
        }
        return FailedRequestReport(checked, wrong, checkedSources)
    }

    private class FailingTransport(private val answer: () -> SourceByteResponse) : SourceHttpClient {
        var requests = 0
            private set

        override suspend fun get(url: String, query: Map<String, String>, headers: Map<String, String>): SourceHttpResponse {
            val response = getBytes(url, query, headers, range = null, maxBodyBytes = 0)
            return SourceHttpResponse(response.statusCode, response.body.decodeToString(), response.headers)
        }

        override suspend fun getBytes(
            url: String,
            query: Map<String, String>,
            headers: Map<String, String>,
            range: SourceByteRange?,
            maxBodyBytes: Int,
        ): SourceByteResponse {
            requests += 1
            return answer()
        }

        override suspend fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): SourceHttpResponse =
            get(url, form, headers)

        override suspend fun postJson(url: String, body: String, headers: Map<String, String>): SourceHttpResponse =
            get(url, emptyMap(), headers)
    }

    private fun customFamilyAdapters(http: SourceHttpClient): Map<ProtocolFamily, SourceAdapter> {
        val address = (SiteAddress.parse("booru.example") as SiteAddressParse.Valid).address
        return SiteFamilyRegistry.default().families.associate { siteFamily ->
            val profile = SiteProfile(address.derivedSourceId, address, "Booru", siteFamily.family.name, addedAtEpochMs = 0)
            siteFamily.family to siteFamily.createAdapter(profile, SourceKey.of(profile), SiteRequestContext(http))
        }
    }
}
