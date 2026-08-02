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

internal class LiveSearchCoordinatorRouteTestMediaTest : LiveSearchCoordinatorRouteTestFixture() {
    @Test
    fun `live media candidates are reachable with app headers`() = runTest {
        assumeTrue("Live source media smoke is opt-in", System.getProperty("theoria.liveSources") == "true")

        val credentialedSources = credentialedSourcesFromEnvironment()
        val appExposedSources = exposedRealSources(
            rule34XxxConfigured = SourceKey.RULE34XXX in credentialedSources,
        )
        val liveRegistrySources = liveRegistrySources(appExposedSources)
        val cases = selectedProbeCases(
            credentialedSources = credentialedSources,
            liveRegistrySources = liveRegistrySources,
        ).filter { probeCase -> probeCase.mediaProbe }
        assumeTrue("No live source media cases selected", cases.isNotEmpty())

        val httpClient = liveHttpClient()
        val registry = realRegistry(liveRegistrySources, httpClient)
        val issues = mutableListOf<String>()

        cases.forEach { probeCase ->
            val run = executeSeededSearch(registry, probeCase, issues) ?: return@forEach
            val post = run.result.posts.firstOrNull()
            val candidate = post?.let(::postDownloadMediaCandidate)
            if (candidate == null) {
                issues += "${probeCase.source.name}: app media policy found no downloadable candidate"
                return@forEach
            }

            val probe = try {
                probeMediaCandidate(
                    httpClient = httpClient,
                    url = candidate.url,
                    headers = candidate.requestHeaders,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                issues += "${probeCase.source.name}: media URL ${sanitizeUrlForReport(candidate.url)} failed with ${error.javaClass.simpleName}"
                return@forEach
            }
            val statusCode = probe.statusCode
            if (statusCode !in 200..399) {
                issues += "${probeCase.source.name}: media URL ${sanitizeUrlForReport(candidate.url)} returned $statusCode"
            }
        }

        assertTrue(issues.joinToString(separator = "\n"), issues.isEmpty())
    }

}
