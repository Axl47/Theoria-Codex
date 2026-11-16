package com.theoriacodex.sources

import com.theoriacodex.domain.diagnostics.OffMainChecker
import com.theoriacodex.domain.diagnostics.OffMainWork
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.source.ProtocolFamily
import com.theoriacodex.domain.source.SiteProbeResult
import com.theoriacodex.sources.credentials.PixivAuthTokens
import com.theoriacodex.sources.credentials.PixivCredentialSession
import com.theoriacodex.sources.credentials.SourceCredentialsProvider
import com.theoriacodex.sources.custom.CustomSiteHttpClient
import com.theoriacodex.sources.custom.FixtureSiteTransport
import com.theoriacodex.sources.custom.SiteFamilyRegistry
import com.theoriacodex.sources.custom.SiteProber
import com.theoriacodex.sources.hitomi.HitomiMediaUrlResolver
import com.theoriacodex.sources.hitomi.HitomiNozomi
import com.theoriacodex.sources.hitomi.HitomiNozomiRequest
import com.theoriacodex.sources.hitomi.RoutingHitomiHttpClient
import com.theoriacodex.sources.http.SourceByteRange
import com.theoriacodex.sources.http.SourceByteResponse
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.http.SourceHttpResponse
import com.theoriacodex.sources.pixiv.PixivAuthApi
import com.theoriacodex.sources.pixiv.PixivTokenCoordinator
import com.theoriacodex.sources.testing.FakeCredentialsProvider
import com.theoriacodex.sources.testing.FakeHttpClient
import com.theoriacodex.sources.testing.providerPageCredentials
import com.theoriacodex.sources.testing.providerPageFixtures
import com.theoriacodex.sources.testing.providerPageQuery
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Shows that `sourceCall` does what SourceEntryPointCompletenessTest assumes: a boundary call made from a
 * stand-in main thread returns its result, parses, fetches and reads credentials on another thread, and resumes
 * on the caller's thread. The stand-in is a plain single-thread dispatcher, deliberately not a main-dispatcher
 * type, because Compose's dispatcher is not one either.
 */
class SourceMainSafetyContractTest {
    private val standInThread = CompletableFuture<Thread>()
    private val standIn = Executors.newSingleThreadExecutor { task ->
        Thread(task, "stand-in-main").also(standInThread::complete)
    }.asCoroutineDispatcher()
    private val events = ConcurrentLinkedQueue<Event>()
    private var previousChecker: OffMainChecker? = null

    @Before
    fun installChecker() {
        previousChecker = OffMainWork.install { label -> record(label) }
    }

    @After
    fun restoreChecker() {
        OffMainWork.install(previousChecker)
        standIn.close()
    }

    @Test
    fun `every registry adapter searches off the calling thread`() {
        for (fixture in providerPageFixtures().filterNot { it.source in NOT_YET_CONVERTED }) {
            val http = RecordingHttpClient(FakeHttpClient().apply { nextGetResponse = SourceHttpResponse(200, fixture.body) })
            val registry = RealAdapterRegistry(RecordingCredentials(providerPageCredentials()), http, setOf(fixture.source))
            val adapter = requireNotNull(registry.adapterFor(fixture.source))
            assertOffCaller(fixture.source.id, expected = 2) { adapter.search(providerPageQuery(fixture.source), null).items.size }
        }
        val hitomiHttp = RoutingHitomiHttpClient().apply {
            binaryIndexes[HitomiNozomi.urlFor(HitomiNozomiRequest())] = listOf(43, 42)
            textRoutes[HitomiMediaUrlResolver.GG_CONFIGURATION_URL] = resource("/hitomi/2026-07-10/gg-shape.js")
        }
        val hitomi = requireNotNull(
            RealAdapterRegistry(FakeCredentialsProvider(), RecordingHttpClient(hitomiHttp), setOf(SourceKey.HITOMI))
                .adapterFor(SourceKey.HITOMI),
        )
        if (SourceKey.HITOMI !in NOT_YET_CONVERTED) assertOffCaller("hitomi", expected = 2) { hitomi.search(providerPageQuery(SourceKey.HITOMI), null).items.size }
    }

    @Test
    fun `pixiv sign-in and token refresh run off the calling thread`() {
        val authApi = PixivAuthApi(RecordingHttpClient(FakeHttpClient().apply { nextPostResponse = TOKEN_RESPONSE }))
        assertOffCaller("exchange", expected = "new-access") {
            authApi.exchangeAuthorizationCode("code", "verifier", "https://example.com/callback").accessToken
        }
        assertOffCaller("refresh", expected = "new-access") { authApi.refresh("old-refresh").accessToken }
    }

    @Test
    fun `an expired pixiv session refreshes off the calling thread`() {
        val credentials = RecordingCredentials(FakeCredentialsProvider().apply { pixivTokens = PixivAuthTokens("old", "old-refresh", 1L) })
        val authApi = PixivAuthApi(RecordingHttpClient(FakeHttpClient().apply { nextPostResponse = TOKEN_RESPONSE }))
        val coordinator = PixivTokenCoordinator(credentials, authApi)
        assertOffCaller("activeTokens", expected = "new-access") { coordinator.activeTokens().accessToken }
    }

    @Test
    fun `site probing runs off the calling thread`() {
        val transport = FixtureSiteTransport("aibooru")
        val http = CustomSiteHttpClient(10_000, 10_000, 3) { spec -> record(HTTP); transport.execute(spec) }
        val prober = SiteProber(SiteFamilyRegistry.default(), http, builtInHosts = emptyMap())
        assertOffCaller("probe", expected = ProtocolFamily.DANBOORU) {
            (prober.probe("aibooru.online") as SiteProbeResult.Supported).family
        }
    }

    @Test
    fun `an unconfined caller started on the calling thread still searches elsewhere`() {
        val fixture = providerPageFixtures().first { it.source == SourceKey.DANBOORU }
        val http = RecordingHttpClient(FakeHttpClient().apply { nextGetResponse = SourceHttpResponse(200, fixture.body) })
        val adapter = requireNotNull(RealAdapterRegistry(providerPageCredentials(), http, setOf(fixture.source)).adapterFor(fixture.source))
        events.clear()
        var startedOn: Thread? = null
        var count: Int? = null
        runBlocking(standIn) {
            launch(Dispatchers.Unconfined) {
                startedOn = Thread.currentThread()
                count = adapter.search(providerPageQuery(fixture.source), null).items.size
            }.join()
        }
        assertSame(standInThread.get(), startedOn)
        assertEquals(2, count)
        assertOffCallerEvents("unconfined")
    }

    /** Runs [call] on the stand-in thread and checks its result, where its work ran, and where it resumed. */
    private fun <T> assertOffCaller(row: String, expected: T, call: suspend () -> T) {
        events.clear()
        val (result, resumedOn) = runBlocking(standIn) { call() to Thread.currentThread() }
        assertEquals(row, expected, result)
        assertOffCallerEvents(row)
        assertSame("$row resumed elsewhere", standInThread.get(), resumedOn)
    }

    private fun assertOffCallerEvents(row: String) {
        val seen = events.toList()
        assertTrue("$row: no marked parse ran", seen.any { it.kind == OffMainWork.SOURCE_PARSE })
        assertEquals("$row: work ran on the calling thread", emptyList<Event>(), seen.filter { it.thread === standInThread.get() })
    }

    private fun resource(path: String) = requireNotNull(javaClass.getResource(path)).readText()

    private fun record(kind: String) {
        events += Event(kind, Thread.currentThread())
    }

    private data class Event(val kind: String, val thread: Thread)

    /** Records the thread of every request, then answers from [delegate]. */
    private inner class RecordingHttpClient(private val delegate: SourceHttpClient) : SourceHttpClient {
        override suspend fun get(url: String, query: Map<String, String>, headers: Map<String, String>): SourceHttpResponse {
            record(HTTP)
            return delegate.get(url, query, headers)
        }

        override suspend fun getBytes(
            url: String,
            query: Map<String, String>,
            headers: Map<String, String>,
            range: SourceByteRange?,
            maxBodyBytes: Int,
        ): SourceByteResponse {
            record(HTTP)
            return delegate.getBytes(url, query, headers, range, maxBodyBytes)
        }

        override suspend fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): SourceHttpResponse {
            record(HTTP)
            return delegate.postForm(url, form, headers)
        }

        override suspend fun postJson(url: String, body: String, headers: Map<String, String>): SourceHttpResponse {
            record(HTTP)
            return delegate.postJson(url, body, headers)
        }
    }

    /** Records the thread of every credential read. */
    private inner class RecordingCredentials(
        private val delegate: SourceCredentialsProvider,
    ) : SourceCredentialsProvider by delegate {
        override suspend fun getPixivTokens(): PixivAuthTokens? = delegate.getPixivTokens().also { record(CREDENTIALS) }
        override suspend fun getPixivSession(): PixivCredentialSession = delegate.getPixivSession().also { record(CREDENTIALS) }
        override suspend fun getGelbooruCredentials() = delegate.getGelbooruCredentials().also { record(CREDENTIALS) }
        override suspend fun getRule34XxxCredentials() = delegate.getRule34XxxCredentials().also { record(CREDENTIALS) }
    }

    private companion object {
        /** Sources Phase 1 has not converted yet. Empty, and removed, once the whole boundary is wrapped. */
        val NOT_YET_CONVERTED = setOf(
            SourceKey.IWARA,
            SourceKey.RULE34VIDEO, SourceKey.RULE34GEN, SourceKey.RULE34PAHEAL,
        )
        const val HTTP = "http"
        const val CREDENTIALS = "credentials"
        val TOKEN_RESPONSE = SourceHttpResponse(200, """{"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600}""")
    }
}
