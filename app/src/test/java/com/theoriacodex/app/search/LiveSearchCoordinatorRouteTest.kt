package com.theoriacodex.app.search

import com.theoriacodex.app.creator.CreatorProfileCoordinator
import com.theoriacodex.app.media.PostMediaKind
import com.theoriacodex.app.media.postDownloadMediaCandidate
import com.theoriacodex.app.media.postMediaItems
import com.theoriacodex.app.media.postPlaybackMediaCandidate
import com.theoriacodex.app.source.exposedRealSources
import com.theoriacodex.app.source.requestHeaders
import com.theoriacodex.app.viewer.ViewerMediaOverviewKind
import com.theoriacodex.app.viewer.viewerMediaOverviewItems
import com.theoriacodex.domain.adapter.FacetedSearchScope
import com.theoriacodex.domain.model.PostId
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

class LiveSearchCoordinatorRouteTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

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
            val coordinator = executeSeededSearch(registry, probeCase, issues) ?: return@forEach

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
                coordinator.refreshAutocompleteSuggestions(scopedPrefix)
                if (coordinator.autocompleteSuggestions.isEmpty()) {
                    issues += "${probeCase.source.name}: ${autocompleteProbe.checkName} returned no tags for prefix $scopedPrefix"
                }
                val expectedFacet = autocompleteProbe.facet
                if (
                    expectedFacet != null &&
                    coordinator.facetedAutocompleteSuggestions.none { suggestion ->
                        suggestion.facet == expectedFacet &&
                            suggestion.sourceNamespace == autocompleteProbe.sourceNamespace
                    }
                ) {
                    issues += "${probeCase.source.name}: ${autocompleteProbe.checkName} lost ${autocompleteProbe.sourceNamespace ?: expectedFacet.name.lowercase()} taxonomy"
                }
            }

            if (probeCase.trendingProbe) {
                coordinator.loadTrendingTags(forceRefresh = true)
                if (coordinator.trendingTags.isEmpty()) {
                    issues += "${probeCase.source.name}: trending tags returned no tags"
                }
            }
        }

        assertTrue(issues.joinToString(separator = "\n"), issues.isEmpty())
    }

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
            val coordinator = executeSeededSearch(registry, probeCase, issues) ?: return@forEach
            val post = coordinator.results.firstOrNull()
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

        val newest = searchCoordinator(registry, "hitomi-newest")
        newest.initialize()
        newest.setMode(QueryMode.Source(SourceKey.HITOMI))
        newest.setSort(SortMode.NEWEST)
        newest.applyDraft()
        assertCoordinatorSucceeded("Hitomi blank Newest", newest)
        assertTrue(
            "Hitomi blank Newest returned a non-Hitomi post",
            newest.results.all { post -> post.id.source == SourceKey.HITOMI },
        )

        val global = searchCoordinator(registry, "hitomi-global-girl")
        global.initialize()
        global.setMode(QueryMode.Source(SourceKey.HITOMI))
        assertTrue("Hitomi rejected global girl search", global.commitTagInput("girl"))
        global.applyDraft()
        assertCoordinatorSucceeded("Hitomi global girl", global)
        assertEquals(
            "Hitomi global girl returned duplicate post identities",
            global.results.size,
            global.results.map { post -> post.id }.distinct().size,
        )

        val typed = searchCoordinator(registry, "hitomi-typed")
        typed.initialize()
        typed.setMode(QueryMode.Source(SourceKey.HITOMI))
        assertTrue("Hitomi rejected artist:najar", typed.commitTagInput("artist:najar"))
        assertTrue("Hitomi rejected type:artistcg", typed.commitTagInput("type:artistcg"))
        typed.setSort(SortMode.POPULAR)
        typed.applyDraft()
        assertCoordinatorSucceeded("Hitomi typed Popular", typed)
        assertEquals(
            listOf(
                SearchTerm("najar", SearchFacet.ARTIST, "artist"),
                SearchTerm("artistcg", SearchFacet.TYPE, "type"),
            ),
            typed.appliedQuery.includeTerms,
        )
        assertTrue(
            "Hitomi typed Popular returned a post outside the requested artistcg taxonomy",
            typed.results.all { post ->
                post.taxonomy.any { term ->
                    term.value == "najar" && term.facet == SearchFacet.ARTIST
                } && post.taxonomy.any { term ->
                    term.value == "artistcg" && term.facet == SearchFacet.TYPE
                }
            },
        )

        val suggestions = searchCoordinator(registry, "hitomi-autocomplete")
        suggestions.initialize()
        suggestions.setMode(QueryMode.Source(SourceKey.HITOMI))
        suggestions.refreshAutocompleteSuggestions("naj")
        assertTrue(
            "Hitomi global autocomplete did not preserve the Najar artist taxonomy",
            suggestions.facetedAutocompleteSuggestions.any { suggestion ->
                suggestion.text == "najar" &&
                    suggestion.facet == SearchFacet.ARTIST &&
                    suggestion.sourceNamespace == "artist"
            },
        )
        suggestions.refreshAutocompleteSuggestions("artist:naj")
        assertEquals(
            FacetedSearchScope(SearchFacet.ARTIST, "artist"),
            suggestions.selectedSearchScope,
        )
        assertTrue(
            "Hitomi Artist autocomplete did not return Najar",
            suggestions.facetedAutocompleteSuggestions.any { suggestion -> suggestion.text == "najar" },
        )
        assertTrue(
            "Hitomi Artist autocomplete leaked another taxonomy",
            suggestions.facetedAutocompleteSuggestions.all { suggestion ->
                suggestion.facet == SearchFacet.ARTIST && suggestion.sourceNamespace == "artist"
            },
        )

        val animatedGallery = requireNotNull(
            typed.resolvePost(
                PostId(SourceKey.HITOMI, ANIMATED_GALLERY_ID),
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
            typed.resolvePost(
                PostId(SourceKey.HITOMI, ANIME_GALLERY_ID),
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

    private suspend fun executeSeededSearch(
        registry: RealAdapterRegistry,
        probeCase: ProviderProbeCase,
        issues: MutableList<String>,
    ): SearchCoordinator? {
        val coordinator = searchCoordinator(registry, probeCase.source.name.lowercase())
        coordinator.initialize()
        val mode = QueryMode.Source(probeCase.source)
        coordinator.setMode(mode)
        probeCase.includeTerms.forEach { term ->
            if (!coordinator.addIncludeTerm(term)) {
                issues += "${probeCase.source.name}: could not commit seeded term $term"
                return null
            }
        }
        probeCase.includeTags.forEach { term ->
            if (!coordinator.commitTagInput(term)) {
                issues += "${probeCase.source.name}: could not commit seeded term $term"
                return null
            }
        }
        coordinator.setSort(probeCase.sort)
        coordinator.applyDraft()

        if (coordinator.errorMessage != null) {
            issues += "${probeCase.source.name}: search error ${coordinator.errorMessage}"
        }
        if (coordinator.results.isEmpty()) {
            val seededTerms = (probeCase.includeTerms.map { term ->
                "${term.sourceNamespace?.let { namespace -> "$namespace:" }.orEmpty()}${term.value}"
            } + probeCase.includeTags).joinToString()
            issues += "${probeCase.source.name}: seeded search returned no posts for $seededTerms"
        }
        val failedStatuses = coordinator.statuses.filter { status ->
            status.errorMessage != null || status.failureReason != null
        }
        if (failedStatuses.isNotEmpty()) {
            issues += "${probeCase.source.name}: status failures ${failedStatuses.joinToString { it.errorMessage ?: it.failureReason?.name.orEmpty() }}"
        }
        return coordinator
    }

    private fun searchCoordinator(
        registry: RealAdapterRegistry,
        storeName: String,
    ): SearchCoordinator {
        return SearchCoordinator(
            registry = registry,
            tagSuggestionStore = FileBackedTagSuggestionStore(
                storeFile = File(tempFolder.newFolder(storeName), "tag_suggestions.json"),
            ),
        )
    }

    private fun assertCoordinatorSucceeded(label: String, coordinator: SearchCoordinator) {
        assertNull("$label failed: ${coordinator.errorMessage}", coordinator.errorMessage)
        assertTrue("$label returned no posts", coordinator.results.isNotEmpty())
        val failedStatuses = coordinator.statuses.filter { status ->
            status.errorMessage != null || status.failureReason != null
        }
        assertTrue(
            "$label returned failed statuses: ${failedStatuses.joinToString { status -> status.errorMessage ?: status.failureReason?.name.orEmpty() }}",
            failedStatuses.isEmpty(),
        )
    }

    private fun liveHttpClient(): DefaultSourceHttpClient {
        return DefaultSourceHttpClient(
            connectTimeoutMs = 8_000,
            readTimeoutMs = 12_000,
            maxRetries = 0,
        )
    }

    private fun realRegistry(
        liveRegistrySources: Set<SourceKey>,
        httpClient: SourceHttpClient = liveHttpClient(),
    ): RealAdapterRegistry {
        return RealAdapterRegistry(
            credentialsProvider = EnvironmentCredentialsProvider,
            httpClient = httpClient,
            exposedSources = liveRegistrySources,
        )
    }

    private suspend fun probeMediaCandidate(
        httpClient: SourceHttpClient,
        url: String,
        headers: Map<String, String>,
        range: SourceByteRange = GENERIC_MEDIA_RANGE,
        maxBodyBytes: Int = GENERIC_MEDIA_PROBE_BYTES,
    ): SourceByteResponse {
        return try {
            httpClient.getBytes(
                url = url,
                headers = headers,
                range = range,
                maxBodyBytes = maxBodyBytes,
            )
        } catch (error: CancellationException) {
            throw error
        }
    }

    private fun sanitizeUrlForReport(url: String): String {
        return runCatching {
            val uri = URI(url)
            URI(
                uri.scheme,
                uri.userInfo,
                uri.host,
                uri.port,
                uri.path,
                null,
                null,
            ).toString()
        }.getOrDefault(url.substringBefore('?'))
    }

    private fun selectedProbeCases(
        credentialedSources: Set<SourceKey>,
        liveRegistrySources: Set<SourceKey>,
    ): List<ProviderProbeCase> {
        val requestedSources = requestedSources()
        val configuredCases = System.getProperty("theoria.providerProbeCases")
            ?.takeIf(String::isNotBlank)
            ?.let { caseFile -> ProviderProbeCases.fromJson(File(caseFile).readText()) }
            ?: ProviderProbeCases.defaults
        val cases = if (
            SourceKey.HITOMI in liveRegistrySources &&
            configuredCases.none { probeCase -> probeCase.source == SourceKey.HITOMI }
        ) {
            configuredCases + HITOMI_APP_FALLBACK_CASE
        } else {
            configuredCases
        }
        return cases.filter { probeCase ->
            val sourceSelected = if (requestedSources == null) {
                probeCase.source in liveRegistrySources
            } else {
                probeCase.source in requestedSources
            }
            sourceSelected &&
                probeCase.source in liveRegistrySources &&
                (!probeCase.requiresCredentials || probeCase.source in credentialedSources)
        }
    }

    private fun liveRegistrySources(appExposedSources: Set<SourceKey>): Set<SourceKey> {
        val requestedSources = requestedSources() ?: return appExposedSources
        return appExposedSources.intersect(requestedSources)
    }

    private fun requestedSources(): Set<SourceKey>? {
        val rawSelection = System.getProperty("theoria.liveSources.sources")
            ?.takeIf(String::isNotBlank)
            ?: return null
        return rawSelection.split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map { raw ->
                runCatching { SourceKey.valueOf(raw.uppercase()) }
                    .getOrElse { throw IllegalArgumentException("Unknown live source filter: $raw") }
            }
            .toSet()
    }

    private fun credentialedSourcesFromEnvironment(): Set<SourceKey> {
        return buildSet {
            if (!System.getenv("THEORIA_PIXIV_ACCESS_TOKEN").isNullOrBlank()) {
                add(SourceKey.PIXIV)
            }
            if (
                !System.getenv("THEORIA_RULE34XXX_USER_ID").isNullOrBlank() &&
                !System.getenv("THEORIA_RULE34XXX_API_KEY").isNullOrBlank()
            ) {
                add(SourceKey.RULE34XXX)
            }
            if (
                !System.getenv("THEORIA_GELBOORU_USER_ID").isNullOrBlank() &&
                !System.getenv("THEORIA_GELBOORU_API_KEY").isNullOrBlank()
            ) {
                add(SourceKey.GELBOORU)
            }
        }
    }

    private object EnvironmentCredentialsProvider : SourceCredentialsProvider {
        override suspend fun getPixivTokens(): PixivAuthTokens? {
            val accessToken = System.getenv("THEORIA_PIXIV_ACCESS_TOKEN")?.takeIf(String::isNotBlank) ?: return null
            val refreshToken = System.getenv("THEORIA_PIXIV_REFRESH_TOKEN").orEmpty()
            val expiresAt = System.getenv("THEORIA_PIXIV_EXPIRES_AT_MS")?.toLongOrNull() ?: Long.MAX_VALUE
            return PixivAuthTokens(accessToken = accessToken, refreshToken = refreshToken, expiresAtEpochMs = expiresAt)
        }

        override suspend fun savePixivTokens(tokens: PixivAuthTokens) = Unit

        override suspend fun clearPixivTokens() = Unit

        override suspend fun getGelbooruCredentials(): GelbooruCredentials? {
            val userId = System.getenv("THEORIA_GELBOORU_USER_ID")?.takeIf(String::isNotBlank) ?: return null
            val apiKey = System.getenv("THEORIA_GELBOORU_API_KEY")?.takeIf(String::isNotBlank) ?: return null
            return GelbooruCredentials(userId = userId, apiKey = apiKey)
        }

        override suspend fun saveGelbooruCredentials(credentials: GelbooruCredentials) = Unit

        override suspend fun clearGelbooruCredentials() = Unit

        override suspend fun getRule34XxxCredentials(): Rule34XxxCredentials? {
            val userId = System.getenv("THEORIA_RULE34XXX_USER_ID")?.takeIf(String::isNotBlank) ?: return null
            val apiKey = System.getenv("THEORIA_RULE34XXX_API_KEY")?.takeIf(String::isNotBlank) ?: return null
            return Rule34XxxCredentials(userId = userId, apiKey = apiKey)
        }

        override suspend fun saveRule34XxxCredentials(credentials: Rule34XxxCredentials) = Unit

        override suspend fun clearRule34XxxCredentials() = Unit
    }
}

private const val ANIMATED_GALLERY_ID = "4042375"
private const val ANIME_GALLERY_ID = "7231"
private const val GENERIC_MEDIA_PROBE_BYTES = 64 * 1024
private const val LIVE_MEDIA_PROBE_BYTES = 4 * 1024

private val GENERIC_MEDIA_RANGE = SourceByteRange(
    startInclusive = 0L,
    endInclusive = (GENERIC_MEDIA_PROBE_BYTES - 1).toLong(),
)
private val LIVE_MEDIA_RANGE = SourceByteRange(
    startInclusive = 0L,
    endInclusive = (LIVE_MEDIA_PROBE_BYTES - 1).toLong(),
)
private val HITOMI_APP_FALLBACK_CASE = ProviderProbeCase(
    source = SourceKey.HITOMI,
    includeTerms = listOf(
        SearchTerm("najar", SearchFacet.ARTIST, "artist"),
        SearchTerm("artistcg", SearchFacet.TYPE, "type"),
    ),
    sort = SortMode.POPULAR,
    autocompleteProbes = listOf(
        ProviderAutocompleteProbe(prefix = "naj"),
        ProviderAutocompleteProbe(
            prefix = "naj",
            checkName = "autocomplete-artist",
            facet = SearchFacet.ARTIST,
            sourceNamespace = "artist",
        ),
    ),
    trendingProbe = false,
)

private fun String.isWebPUrl(): Boolean {
    return substringBefore('?').substringBefore('#').endsWith(".webp", ignoreCase = true)
}

private fun String.isMp4Url(): Boolean {
    return substringBefore('?').substringBefore('#').endsWith(".mp4", ignoreCase = true)
}

private fun ByteArray.isWebP(): Boolean {
    if (size < 12) return false
    return copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
        copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP"
}

private fun ByteArray.hasMp4FtypBox(): Boolean {
    if (size < 8) return false
    return (0..size - 4).any { index ->
        this[index] == 'f'.code.toByte() &&
            this[index + 1] == 't'.code.toByte() &&
            this[index + 2] == 'y'.code.toByte() &&
            this[index + 3] == 'p'.code.toByte()
    }
}

private object CancellingSourceHttpClient : SourceHttpClient {
    override suspend fun get(
        url: String,
        query: Map<String, String>,
        headers: Map<String, String>,
    ): SourceHttpResponse = error("Text GET is not used by the cancellation probe")

    override suspend fun getBytes(
        url: String,
        query: Map<String, String>,
        headers: Map<String, String>,
        range: SourceByteRange?,
        maxBodyBytes: Int,
    ): SourceByteResponse {
        throw CancellationException("probe cancelled")
    }

    override suspend fun postForm(
        url: String,
        form: Map<String, String>,
        headers: Map<String, String>,
    ): SourceHttpResponse = error("Form POST is not used by the cancellation probe")
}
