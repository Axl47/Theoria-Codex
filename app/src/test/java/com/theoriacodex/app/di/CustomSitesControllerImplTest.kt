package com.theoriacodex.app.di

import com.theoriacodex.app.customsites.CredentialEdit
import com.theoriacodex.app.customsites.CustomSiteAvailability
import com.theoriacodex.app.customsites.CustomSiteDraft
import com.theoriacodex.app.customsites.CustomSiteEdit
import com.theoriacodex.app.customsites.CustomSiteMutation
import com.theoriacodex.app.customsites.FakeBrowserCookieStore
import com.theoriacodex.app.customsites.SiteCookieBridge
import com.theoriacodex.app.sourceauth.InMemorySiteCredentialsStore
import com.theoriacodex.data.repository.InMemorySettingsRepository
import com.theoriacodex.data.repository.InMemorySiteProfileRepository
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.UnifiedSearchOrchestrator
import com.theoriacodex.domain.source.ProtocolFamily
import com.theoriacodex.domain.source.SiteAddress
import com.theoriacodex.domain.source.SiteAddressParse
import com.theoriacodex.domain.source.ProbeFailure
import com.theoriacodex.domain.source.SiteCredentials
import com.theoriacodex.domain.source.SiteHeaders
import com.theoriacodex.domain.source.SiteProbeResult
import com.theoriacodex.domain.source.SiteProfile
import com.theoriacodex.sources.custom.FamilyProbe
import com.theoriacodex.sources.custom.GelbooruFamily
import com.theoriacodex.sources.custom.SiteFamily
import com.theoriacodex.sources.custom.SiteFamilyRegistry
import com.theoriacodex.sources.custom.SiteProber
import com.theoriacodex.sources.custom.SiteRequestContext
import com.theoriacodex.sources.http.HostOutageKind
import com.theoriacodex.sources.http.HostOutageTracker
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.http.SourceHttpResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The custom-site graph end to end, without restarts: store → runtime → registry, and the controller. */
@OptIn(ExperimentalCoroutinesApi::class)
class CustomSitesControllerImplTest {
    private val profiles = InMemorySiteProfileRepository()
    private val keys = InMemorySiteCredentialsStore()
    private val settings = InMemorySettingsRepository()
    private val family = ScriptedFamily()
    private val families = SiteFamilyRegistry(listOf(family), emptyList())
    private var libraryHasData = false
    private var now = 1_000L
    private val outages = HostOutageTracker()
    private val cookieStore = FakeBrowserCookieStore()

    /** Each install: the custom sources installed, and the sources whose adapters were visible at that moment. */
    private val installs = mutableListOf<Pair<Set<SourceKey>, Set<SourceKey>>>()
    private val labels = mutableMapOf<SourceKey, String>()

    private class Graph(val controller: CustomSitesControllerImpl, val registry: CompositeSourceAdapterRegistry)

    private fun TestScope.graph(): Graph {
        val scope: CoroutineScope = backgroundScope + UnconfinedTestDispatcher(testScheduler)
        // The unconfined collector publishes the first (empty) snapshot during construction.
        var built: CustomSourceRuntime? = null
        val runtime = CustomSourceRuntime(profiles, keys, families, NoNetwork, scope) { presentations ->
            installs += presentations.mapTo(mutableSetOf()) { it.source } to built?.snapshot?.value?.adapters?.keys.orEmpty()
            presentations.forEach { labels[it.source] = it.label }
        }
        built = runtime
        val controller = CustomSitesControllerImpl(
            profiles = profiles,
            credentials = keys,
            prober = SiteProber(families, NoNetwork, builtInHosts = emptyMap()),
            runtime = runtime.snapshot,
            settings = settings,
            library = { libraryHasData },
            scope = scope,
            outages = outages,
            cookieBridge = SiteCookieBridge(cookieStore),
            clock = { now },
        )
        return Graph(controller, CompositeSourceAdapterRegistry(NoBuiltIns, NoBuiltIns, runtime.snapshot))
    }

    @Test
    fun `an added site becomes an available source and joins Unified only when asked`() = runTest {
        val graph = graph()

        assertEquals(CustomSiteMutation.Success, graph.controller.add(draft(include = true)))

        val summary = graph.controller.sites.value.single()
        assertEquals(CustomSiteAvailability.AVAILABLE, summary.availability)
        assertEquals("Booru", summary.label)
        assertTrue(summary.includedInUnifiedAndForYou)
        assertEquals(setOf(SITE), graph.registry.availableSources())
        assertEquals(SITE, graph.registry.adapterFor(SITE)?.sourceKey)
        assertTrue(SITE in settings.observeSettings().first().runtime.enabledSources)
    }

    @Test
    fun `presentations are installed before a site's adapter appears and withdrawn after it goes`() = runTest {
        val graph = graph()

        graph.controller.add(draft())
        assertTrue("installed while the adapter was still hidden", installs.any { (installed, visible) -> SITE in installed && SITE !in visible })
        assertEquals("Booru", labels[SITE])

        installs.clear()
        graph.controller.remove(SITE.id)
        // Kept while the adapter is still visible; withdrawn once it is gone.
        assertEquals(listOf(setOf(SITE) to setOf(SITE), emptySet<SourceKey>() to emptySet()), installs)
    }

    @Test
    fun `a restored keyed site is unavailable until keys are entered, and again after they are cleared`() = runTest {
        profiles.mergeRestored(listOf(profile(credentialsRequired = true)))
        val graph = graph()
        assertEquals(CustomSiteAvailability.NEEDS_CREDENTIALS, graph.controller.sites.value.single().availability)
        assertNull(graph.registry.adapterFor(SITE))

        graph.controller.update(SITE.id, CustomSiteEdit(credentials = CredentialEdit.Replace(SiteCredentials("42", "k"))))

        assertEquals(CustomSiteAvailability.AVAILABLE, graph.controller.sites.value.single().availability)
        assertTrue(graph.controller.sites.value.single().hasCredentials)
        assertNotNull(graph.registry.adapterFor(SITE))

        graph.controller.update(SITE.id, CustomSiteEdit(credentials = CredentialEdit.Clear))

        assertEquals(CustomSiteAvailability.NEEDS_CREDENTIALS, graph.controller.sites.value.single().availability)
        assertEquals(emptySet<SourceKey>(), graph.registry.availableSources())
    }

    @Test
    fun `a family this version does not know is kept and reported`() = runTest {
        profiles.mergeRestored(listOf(profile().copy(familyId = "MOEBOORU")))
        val graph = graph()

        assertEquals(CustomSiteAvailability.UNSUPPORTED_FAMILY, graph.controller.sites.value.single().availability)
        assertNull(graph.registry.adapterFor(SITE))
    }

    @Test
    fun `re-adding a site with library data asks before reconnecting`() = runTest {
        val graph = graph()
        libraryHasData = true

        assertEquals(CustomSiteMutation.ReconnectConfirmationNeeded("booru.example"), graph.controller.add(draft()))
        assertEquals(emptyList<SiteProfile>(), profiles.observeProfiles().first())
        assertEquals(CustomSiteMutation.Success, graph.controller.add(draft().copy(confirmReconnect = true)))
        assertEquals(CustomSiteMutation.DuplicateHost(SITE.id), graph.controller.add(draft()))
    }

    @Test
    fun `labels are trimmed and bounded`() = runTest {
        val graph = graph()

        assertEquals(CustomSiteMutation.InvalidLabel(SiteProfile.MAX_LABEL_LENGTH), graph.controller.add(draft(label = "  ")))
        assertEquals(CustomSiteMutation.InvalidLabel(SiteProfile.MAX_LABEL_LENGTH), graph.controller.add(draft(label = "x".repeat(41))))
        graph.controller.add(draft(label = "  Mine  "))
        assertEquals("Mine", graph.controller.sites.value.single().label)
    }

    @Test
    fun `removing a site drops its profile, keys and opt-in`() = runTest {
        val graph = graph()
        graph.controller.add(draft(include = true).copy(credentials = SiteCredentials("42", "k")))

        assertEquals(CustomSiteMutation.Success, graph.controller.remove(SITE.id))

        assertEquals(emptyList<Any>(), graph.controller.sites.value)
        assertNull(keys.get(SITE.id))
        assertFalse(SITE in settings.observeSettings().first().runtime.enabledSources)
        assertEquals(emptySet<SourceKey>(), graph.registry.availableSources())
        assertEquals(CustomSiteMutation.NotFound, graph.controller.remove(SITE.id))
    }

    @Test
    fun `re-test refreshes the verified time on the same family and never switches family`() = runTest {
        val graph = graph()
        graph.controller.add(draft())
        now = 2_000L

        assertTrue(graph.controller.retest(SITE.id) is SiteProbeResult.Supported)
        assertEquals(2_000L, graph.controller.sites.value.single().verifiedAtEpochMs)

        now = 3_000L
        family.answer = FamilyProbe.NoMatch
        assertTrue(graph.controller.retest(SITE.id) is SiteProbeResult.NotABooru)
        assertEquals(2_000L, graph.controller.sites.value.single().verifiedAtEpochMs)
        assertEquals(ProtocolFamily.GELBOORU_02, graph.controller.sites.value.single().family)
    }

    @Test
    fun `checking a verification probes with the browser agent and changes nothing`() = runTest {
        val graph = graph()
        val stored = SiteHeaders(userAgent = "Old/1", referer = "https://ref.example/")
        graph.controller.add(draft().copy(headers = stored))
        outages.record("https://booru.example/posts.json", HostOutageKind.CHALLENGE)
        now = 5_000L

        assertTrue(graph.controller.checkVerification(SITE.id, "WebView/2") is SiteProbeResult.Supported)
        assertEquals(SiteHeaders(userAgent = "WebView/2", referer = "https://ref.example/"), family.probedWith)
        family.answer = FamilyProbe.Failed(ProbeFailure.BLOCKED)
        assertEquals(SiteProbeResult.Unreachable(ADDRESS, ProbeFailure.BLOCKED), graph.controller.checkVerification(SITE.id, "WebView/2"))

        val site = graph.controller.sites.value.single()
        assertEquals(stored, site.headers)
        assertEquals(1_000L, site.verifiedAtEpochMs)
        assertEquals(setOf("booru.example"), outages.outages.value.keys)
        assertEquals(0, cookieStore.flushes)
    }

    @Test
    fun `completing a verification saves the agent beside the other headers, clears the outage and flushes cookies`() = runTest {
        val graph = graph()
        graph.controller.add(draft().copy(headers = SiteHeaders(userAgent = "Old/1", referer = "https://ref.example/")))
        outages.record("https://booru.example/posts.json", HostOutageKind.CHALLENGE)
        now = 5_000L

        graph.controller.completeVerification(SITE.id, "WebView/2", working = true)

        assertEquals(setOf("booru.example"), outages.working.value)
        val site = graph.controller.sites.value.single()
        assertEquals(SiteHeaders(userAgent = "WebView/2", referer = "https://ref.example/"), site.headers)
        assertEquals(5_000L, site.verifiedAtEpochMs)
        assertTrue(outages.outages.value.isEmpty())
        assertEquals(1, cookieStore.flushes)
        assertNull("completing makes no request", family.probedWith)
    }

    @Test
    fun `removing a site forgets its cookies`() = runTest {
        val graph = graph()
        graph.controller.add(draft())

        graph.controller.remove(SITE.id)

        assertEquals(listOf("https://booru.example/"), cookieStore.expired)
    }

    @Test
    fun `the unified orchestrator is rebuilt only when the custom sites change`() = runTest {
        val graph = graph()
        graph.controller.add(draft())
        val first = graph.registry.unifiedOrchestrator()

        assertSame(first, graph.registry.unifiedOrchestrator())
        graph.controller.update(SITE.id, CustomSiteEdit(label = "Renamed"))
        assertNotSame(first, graph.registry.unifiedOrchestrator())
    }

    private fun draft(label: String = "Booru", include: Boolean = false) = CustomSiteDraft(
        probe = SiteProbeResult.Supported(ADDRESS, ProtocolFamily.GELBOORU_02, "booru.example", null, credentialsRequired = false),
        label = label,
        includeInUnifiedAndForYou = include,
    )

    private fun profile(credentialsRequired: Boolean = false) = SiteProfile(
        sourceId = SITE.id,
        address = ADDRESS,
        label = "Booru",
        familyId = ProtocolFamily.GELBOORU_02.name,
        credentialsRequired = credentialsRequired,
        addedAtEpochMs = 0,
    )

    /** Probes answer [answer]; adapters are the real Gelbooru-family ones (no request is made). */
    private class ScriptedFamily : SiteFamily by GelbooruFamily {
        var answer: FamilyProbe = FamilyProbe.Match(suggestedLabel = null, searchTermLimit = null)

        /** The headers of the latest probe, to show which agent a verification probed with. */
        var probedWith: SiteHeaders? = null

        override suspend fun probe(address: SiteAddress, context: SiteRequestContext): FamilyProbe {
            probedWith = context.headers
            return answer
        }
    }

    private object NoNetwork : SourceHttpClient {
        override suspend fun get(url: String, query: Map<String, String>, headers: Map<String, String>): SourceHttpResponse =
            error("No network in this test")

        override suspend fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): SourceHttpResponse =
            error("No network in this test")
    }

    private object NoBuiltIns : SourceAdapterRegistry {
        private val empty = UnifiedSearchOrchestrator(emptyMap())
        override fun availableSources(): Set<SourceKey> = emptySet()
        override fun adapterFor(sourceKey: SourceKey): SourceAdapter? = null
        override fun unifiedOrchestrator(): UnifiedSearchOrchestrator = empty
    }

    private companion object {
        val ADDRESS = (SiteAddress.parse("booru.example") as SiteAddressParse.Valid).address
        val SITE: SourceKey = requireNotNull(SourceKey.parse(ADDRESS.derivedSourceId))
    }
}
