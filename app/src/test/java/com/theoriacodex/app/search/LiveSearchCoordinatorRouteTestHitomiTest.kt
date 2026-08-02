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

internal class LiveSearchCoordinatorRouteTestHitomiTest : LiveSearchCoordinatorRouteTestFixture() {
    @Test
    fun `live Hitomi routes typed search and mixed media through app contracts`() = runTest {
        assumeTrue("Live source route smoke is opt-in", System.getProperty("theoria.liveSources") == "true")

        val appExposedSources = exposedRealSources(rule34XxxConfigured = false)
        assumeTrue(
            "Hitomi live source was not selected",
            SourceKey.HITOMI in liveRegistrySources(appExposedSources),
        )

        val httpClient = liveHttpClient()
        val registry = realRegistry(setOf(SourceKey.HITOMI), httpClient)

        val newest = executeSourceSearch(
            registry,
            "hitomi-newest",
            sourceQuery(SourceKey.HITOMI, sort = SortMode.NEWEST),
        )
        assertCoordinatorSucceeded("Hitomi blank Newest", newest.result)
        assertTrue(
            "Hitomi blank Newest returned a non-Hitomi post",
            newest.result.posts.all { post -> post.id.source == SourceKey.HITOMI },
        )

        val global = executeSourceSearch(
            registry,
            "hitomi-global-girl",
            sourceQuery(SourceKey.HITOMI, includeTerms = listOf(SearchTerm("girl"))),
        )
        assertCoordinatorSucceeded("Hitomi global girl", global.result)
        assertEquals(
            "Hitomi global girl returned duplicate post identities",
            global.result.posts.size,
            global.result.posts.map { post -> post.id }.distinct().size,
        )

        val character = executeSourceSearch(
            registry,
            "hitomi-character-klee",
            sourceQuery(
                SourceKey.HITOMI,
                includeTerms = listOf(SearchTerm("klee", SearchFacet.CHARACTER, "character")),
            ),
        )
        assertCoordinatorSucceeded("Hitomi character:klee", character.result)
        assertEquals(
            "Hitomi character:klee returned duplicate post identities",
            character.result.posts.size,
            character.result.posts.map { post -> post.id }.distinct().size,
        )

        val typed = executeSourceSearch(
            registry,
            "hitomi-typed",
            sourceQuery(
                SourceKey.HITOMI,
                includeTerms = listOf(
                    SearchTerm("najar", SearchFacet.ARTIST, "artist"),
                    SearchTerm("artistcg", SearchFacet.TYPE, "type"),
                ),
                sort = SortMode.POPULAR,
            ),
        )
        assertCoordinatorSucceeded("Hitomi typed Popular", typed.result)
        assertEquals(
            listOf(
                SearchTerm("najar", SearchFacet.ARTIST, "artist"),
                SearchTerm("artistcg", SearchFacet.TYPE, "type"),
            ),
            typed.query.includeTerms,
        )
        assertTrue(
            "Hitomi typed Popular returned a post outside the requested artistcg taxonomy",
            typed.result.posts.all { post ->
                post.taxonomy.any { term ->
                    term.value == "najar" && term.facet == SearchFacet.ARTIST
                } && post.taxonomy.any { term ->
                    term.value == "artistcg" && term.facet == SearchFacet.TYPE
                }
            },
        )

        val suggestions = searchCoordinator(registry, "hitomi-autocomplete")
        suggestions.initializeRoute()
        val suggestionQuery = sourceQuery(SourceKey.HITOMI)
        val globalSuggestions = suggestions.fetchAutocomplete(
            suggestionQuery,
            SearchSourceScope.Single(SourceKey.HITOMI),
            FacetedSearchScope.All,
            "naj",
            emptyList(),
        )
        assertTrue(
            "Hitomi global autocomplete did not preserve the Najar artist taxonomy",
            globalSuggestions.facetedAutocomplete.any { suggestion ->
                suggestion.text == "najar" &&
                    suggestion.facet == SearchFacet.ARTIST &&
                    suggestion.sourceNamespace == "artist"
            },
        )
        val artistSuggestions = suggestions.fetchAutocomplete(
            suggestionQuery,
            SearchSourceScope.Single(SourceKey.HITOMI),
            FacetedSearchScope.All,
            "artist:naj",
            emptyList(),
        )
        assertEquals(
            FacetedSearchScope(SearchFacet.ARTIST, "artist"),
            artistSuggestions.selectedScope,
        )
        assertTrue(
            "Hitomi Artist autocomplete did not return Najar",
            artistSuggestions.facetedAutocomplete.any { suggestion -> suggestion.text == "najar" },
        )
        assertTrue(
            "Hitomi Artist autocomplete leaked another taxonomy",
            artistSuggestions.facetedAutocomplete.all { suggestion ->
                suggestion.facet == SearchFacet.ARTIST && suggestion.sourceNamespace == "artist"
            },
        )

        val animatedGallery = requireNotNull(
            typed.coordinator.resolvePostForSearch(
                PostId(SourceKey.HITOMI, ANIMATED_GALLERY_ID),
                typed.result.executionKey,
            ),
        ) { "Hitomi gallery $ANIMATED_GALLERY_ID did not resolve" }
        assertTrue(
            "Hitomi gallery $ANIMATED_GALLERY_ID lost its artistcg taxonomy",
            animatedGallery.taxonomy.any { term ->
                term.value == "artistcg" &&
                    term.facet == SearchFacet.TYPE &&
                    term.sourceNamespace == "type"
            },
        )
        val animatedOverview = viewerMediaOverviewItems(animatedGallery)
        assertEquals(44, animatedOverview.size)
        assertEquals((0 until 44).toList(), animatedOverview.map { item -> item.mediaIndex })
        assertTrue(
            "Hitomi gallery $ANIMATED_GALLERY_ID was not mapped as 44 animated images",
            animatedOverview.all { item ->
                item.kind == ViewerMediaOverviewKind.ANIMATED_IMAGE && item.media.isAnimated
            },
        )
        val webPUrl = requireNotNull(animatedOverview.first().posterLocation) {
            "Hitomi gallery $ANIMATED_GALLERY_ID had no WebP overview poster"
        }
        assertTrue("Hitomi overview did not select WebP: $webPUrl", webPUrl.isWebPUrl())
        val webPProbe = probeMediaCandidate(
            httpClient = httpClient,
            url = webPUrl,
            headers = SourceKey.HITOMI.requestHeaders(),
            range = LIVE_MEDIA_RANGE,
            maxBodyBytes = LIVE_MEDIA_PROBE_BYTES,
        )
        assertEquals("Hitomi animated WebP did not honor Range", 206, webPProbe.statusCode)
        assertTrue("Hitomi animated WebP range was empty", webPProbe.body.isNotEmpty())
        assertTrue("Hitomi animated WebP range omitted its RIFF/WEBP signature", webPProbe.body.isWebP())

        val najar = requireNotNull(
            animatedGallery.creatorProfiles.firstOrNull { creator -> creator.profileId == "najar" },
        ) { "Hitomi gallery $ANIMATED_GALLERY_ID did not expose Najar as a creator" }
        assertEquals("artist:najar", najar.uploadsQuery)
        val creator = CreatorProfileCoordinator(registry)
        creator.open(najar)
        assertNull("Hitomi Najar creator uploads failed: ${creator.errorMessage}", creator.errorMessage)
        assertTrue("Hitomi Najar creator uploads were empty", creator.results.isNotEmpty())
        assertTrue(
            "Hitomi Najar creator uploads returned another source",
            creator.results.all { post -> post.id.source == SourceKey.HITOMI },
        )

        val anime = requireNotNull(
            typed.coordinator.resolvePostForSearch(
                PostId(SourceKey.HITOMI, ANIME_GALLERY_ID),
                typed.result.executionKey,
            ),
        ) { "Hitomi anime $ANIME_GALLERY_ID did not resolve" }
        assertTrue(
            "Hitomi anime $ANIME_GALLERY_ID lost its anime taxonomy",
            anime.taxonomy.any { term ->
                term.value == "anime" && term.facet == SearchFacet.TYPE && term.sourceNamespace == "type"
            },
        )
        assertTrue("Hitomi anime unexpectedly exposed a creator", anime.creatorProfiles.isEmpty())
        val animeMedia = postMediaItems(anime)
        assertEquals("Hitomi anime exposed poster records as pages", 1, animeMedia.size)
        val animeOverview = viewerMediaOverviewItems(anime)
        assertEquals(listOf(0), animeOverview.map { item -> item.mediaIndex })
        assertEquals(listOf(ViewerMediaOverviewKind.VIDEO), animeOverview.map { item -> item.kind })
        val video = requireNotNull(postPlaybackMediaCandidate(anime)) {
            "Hitomi anime $ANIME_GALLERY_ID did not expose a playback candidate"
        }
        assertEquals(PostMediaKind.VIDEO, video.kind)
        assertTrue("Hitomi anime playback candidate was not MP4: ${video.url}", video.url.isMp4Url())
        assertEquals(SourceKey.HITOMI.requestHeaders(), video.requestHeaders)
        val mp4Probe = probeMediaCandidate(
            httpClient = httpClient,
            url = video.url,
            headers = video.requestHeaders,
            range = LIVE_MEDIA_RANGE,
            maxBodyBytes = LIVE_MEDIA_PROBE_BYTES,
        )
        assertEquals("Hitomi anime MP4 did not honor Range", 206, mp4Probe.statusCode)
        assertTrue("Hitomi anime MP4 range was empty", mp4Probe.body.isNotEmpty())
        assertTrue("Hitomi anime MP4 range omitted an ftyp box", mp4Probe.body.hasMp4FtypBox())
    }

}
