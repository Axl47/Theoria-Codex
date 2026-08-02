package com.theoriacodex.app.search

import com.theoriacodex.app.creator.CreatorProfileCoordinator
import com.theoriacodex.app.media.PostMediaKind
import com.theoriacodex.app.media.postDownloadMediaCandidate
import com.theoriacodex.app.media.postMediaItems
import com.theoriacodex.app.media.postPlaybackMediaCandidate
import com.theoriacodex.app.source.exposedRealSources
import com.theoriacodex.app.source.requestHeaders
import com.theoriacodex.app.search.state.SearchSourceScope
import com.theoriacodex.app.viewer.ViewerMediaOverviewKind
import com.theoriacodex.app.viewer.viewerMediaOverviewItems
import com.theoriacodex.domain.adapter.FacetedSearchScope
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.RealAdapterRegistry
import com.theoriacodex.sources.credentials.GelbooruCredentials
import com.theoriacodex.sources.credentials.PixivAuthTokens
import com.theoriacodex.sources.credentials.Rule34XxxCredentials
import com.theoriacodex.sources.credentials.SourceCredentialsProvider
import com.theoriacodex.sources.health.ProviderAutocompleteProbe
import com.theoriacodex.sources.health.ProviderProbeCase
import com.theoriacodex.sources.health.ProviderProbeCases
import com.theoriacodex.sources.http.DefaultSourceHttpClient
import com.theoriacodex.sources.http.SourceByteRange
import com.theoriacodex.sources.http.SourceByteResponse
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.http.SourceHttpResponse
import java.io.File
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

internal class LiveSearchCoordinatorRouteTest : LiveSearchCoordinatorRouteTestFixture() {
    @Test
    fun `bounded media probe preserves cancellation`() = runTest {
        var propagated = false
        try {
            probeMediaCandidate(
                httpClient = CancellingSourceHttpClient,
                url = "https://example.com/media.webp",
                headers = emptyMap(),
            )
        } catch (_: CancellationException) {
            propagated = true
        }

        assertTrue("Source HTTP cancellation was swallowed by the app media probe", propagated)
    }

    @Test
    fun `live source filter is strict case normalized and limits registry exposure`() {
        val propertyName = "theoria.liveSources.sources"
        val previous = System.getProperty(propertyName)
        try {
            System.setProperty(propertyName, " hitomi ")
            assertEquals(
                setOf(SourceKey.HITOMI),
                liveRegistrySources(setOf(SourceKey.HITOMI, SourceKey.NHENTAI)),
            )

            System.setProperty(propertyName, "missing")
            val failure = runCatching {
                liveRegistrySources(setOf(SourceKey.HITOMI, SourceKey.NHENTAI))
            }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
        } finally {
            if (previous == null) {
                System.clearProperty(propertyName)
            } else {
                System.setProperty(propertyName, previous)
            }
        }
    }

    @Test
    fun `live seeded searches route through search coordinator`() = runTest {
        assumeTrue("Live source route smoke is opt-in", System.getProperty("theoria.liveSources") == "true")

        val credentialedSources = credentialedSourcesFromEnvironment()
        val appExposedSources = exposedRealSources(
            rule34XxxConfigured = SourceKey.RULE34XXX in credentialedSources,
        )
        val liveRegistrySources = liveRegistrySources(appExposedSources)
        val cases = selectedProbeCases(
            credentialedSources = credentialedSources,
            liveRegistrySources = liveRegistrySources,
        )
        assumeTrue("No live source cases selected", cases.isNotEmpty())

        val registry = realRegistry(liveRegistrySources)
        val issues = mutableListOf<String>()

        cases.forEach { probeCase ->
            val run = executeSeededSearch(registry, probeCase, issues) ?: return@forEach

            val autocompleteProbes = probeCase.autocompleteProbes.ifEmpty {
                listOfNotNull(
                    probeCase.autocompletePrefix
                        ?.takeIf(String::isNotBlank)
                        ?.let { prefix ->
                            ProviderAutocompleteProbe(prefix = prefix)
                        },
                )
            }
            autocompleteProbes.forEach { autocompleteProbe ->
                val scopedPrefix = autocompleteProbe.sourceNamespace
                    ?.let { namespace -> "$namespace:${autocompleteProbe.prefix}" }
                    ?: autocompleteProbe.prefix
                val autocomplete = run.coordinator.fetchAutocomplete(
                    query = run.query,
                    sourceScope = run.sourceScope,
                    selectedScope = FacetedSearchScope.All,
                    input = scopedPrefix,
                    trending = emptyList(),
                )
                if (autocomplete.autocomplete.isEmpty()) {
                    issues += "${probeCase.source.name}: ${autocompleteProbe.checkName} returned no tags for prefix $scopedPrefix"
                }
                val expectedFacet = autocompleteProbe.facet
                if (
                    expectedFacet != null &&
                    autocomplete.facetedAutocomplete.none { suggestion ->
                        suggestion.facet == expectedFacet &&
                            suggestion.sourceNamespace == autocompleteProbe.sourceNamespace
                    }
                ) {
                    issues += "${probeCase.source.name}: ${autocompleteProbe.checkName} lost ${autocompleteProbe.sourceNamespace ?: expectedFacet.name.lowercase()} taxonomy"
                }
            }

            if (probeCase.trendingProbe) {
                val trending = run.coordinator.fetchTrending(
                    run.query,
                    run.sourceScope,
                    forceRefresh = true,
                )
                if (trending.isEmpty()) {
                    issues += "${probeCase.source.name}: trending tags returned no tags"
                }
            }
        }

        assertTrue(issues.joinToString(separator = "\n"), issues.isEmpty())
    }

}
