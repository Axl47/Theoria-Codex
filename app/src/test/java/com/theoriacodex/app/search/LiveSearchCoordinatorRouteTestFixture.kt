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

internal abstract class LiveSearchCoordinatorRouteTestFixture {
    @get:Rule
    val tempFolder = TemporaryFolder()

    protected fun searchCoordinator(
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

    protected suspend fun executeSourceSearch(
        registry: RealAdapterRegistry,
        storeName: String,
        query: Query,
    ): LiveSearchRun {
        val coordinator = searchCoordinator(registry, storeName)
        coordinator.initializeRoute()
        val scope = SearchSourceScope.Single((query.mode as QueryMode.Source).source)
        val result = coordinator.executeInitial(query, scope)
        require(result is SearchExecutionResult.Success) {
            (result as SearchExecutionResult.Failure).message
        }
        return LiveSearchRun(coordinator, query, scope, result)
    }

    protected suspend fun executeSeededSearch(
        registry: RealAdapterRegistry,
        probeCase: ProviderProbeCase,
        issues: MutableList<String>,
    ): LiveSearchRun? {
        val coordinator = searchCoordinator(registry, probeCase.source.name.lowercase())
        coordinator.initializeRoute()
        val query = sourceQuery(
            source = probeCase.source,
            includeTerms = probeCase.includeTerms + probeCase.includeTags.map(::SearchTerm),
            sort = probeCase.sort,
        )
        val scope = SearchSourceScope.Single(probeCase.source)
        val result = coordinator.executeInitial(query, scope)
        if (result is SearchExecutionResult.Failure) {
            issues += "${probeCase.source.name}: search error ${result.message}"
            return null
        }
        result as SearchExecutionResult.Success
        if (result.posts.isEmpty()) {
            val seededTerms = (probeCase.includeTerms.map { term ->
                "${term.sourceNamespace?.let { namespace -> "$namespace:" }.orEmpty()}${term.value}"
            } + probeCase.includeTags).joinToString()
            issues += "${probeCase.source.name}: seeded search returned no posts for $seededTerms"
        }
        val failedStatuses = result.statuses.filter { status ->
            status.errorMessage != null || status.failureReason != null
        }
        if (failedStatuses.isNotEmpty()) {
            issues += "${probeCase.source.name}: status failures ${failedStatuses.joinToString { it.errorMessage ?: it.failureReason?.name.orEmpty() }}"
        }
        return LiveSearchRun(coordinator, query, scope, result)
    }

    protected fun assertCoordinatorSucceeded(label: String, result: SearchExecutionResult.Success) {
        assertTrue("$label returned no posts", result.posts.isNotEmpty())
        val failedStatuses = result.statuses.filter { status ->
            status.errorMessage != null || status.failureReason != null
        }
        assertTrue(
            "$label returned failed statuses: ${failedStatuses.joinToString { status -> status.errorMessage ?: status.failureReason?.name.orEmpty() }}",
            failedStatuses.isEmpty(),
        )
    }

    protected fun liveHttpClient(): DefaultSourceHttpClient {
        return DefaultSourceHttpClient(
            connectTimeoutMs = 8_000,
            readTimeoutMs = 12_000,
            maxRetries = 0,
        )
    }

    protected fun realRegistry(
        liveRegistrySources: Set<SourceKey>,
        httpClient: SourceHttpClient = liveHttpClient(),
    ): RealAdapterRegistry {
        return RealAdapterRegistry(
            credentialsProvider = EnvironmentCredentialsProvider,
            httpClient = httpClient,
            exposedSources = liveRegistrySources,
        )
    }

    protected suspend fun probeMediaCandidate(
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

    protected fun sanitizeUrlForReport(url: String): String {
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

    protected fun selectedProbeCases(
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

    protected fun liveRegistrySources(appExposedSources: Set<SourceKey>): Set<SourceKey> {
        val requestedSources = requestedSources() ?: return appExposedSources
        return appExposedSources.intersect(requestedSources)
    }

    protected fun requestedSources(): Set<SourceKey>? {
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

    protected fun credentialedSourcesFromEnvironment(): Set<SourceKey> {
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

    protected object EnvironmentCredentialsProvider : SourceCredentialsProvider {
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

internal data class LiveSearchRun(
    val coordinator: SearchCoordinator,
    val query: Query,
    val sourceScope: SearchSourceScope,
    val result: SearchExecutionResult.Success,
)

internal fun sourceQuery(
    source: SourceKey,
    includeTerms: List<SearchTerm> = emptyList(),
    sort: SortMode = SortMode.NEWEST,
): Query = Query(
    mode = QueryMode.Source(source),
    includeTerms = includeTerms,
    excludeTerms = emptyList(),
    sort = sort,
    dateRange = null,
    minScore = null,
)

internal const val ANIMATED_GALLERY_ID = "4042375"
internal const val ANIME_GALLERY_ID = "7231"
internal const val GENERIC_MEDIA_PROBE_BYTES = 64 * 1024
internal const val LIVE_MEDIA_PROBE_BYTES = 4 * 1024

internal val GENERIC_MEDIA_RANGE = SourceByteRange(
    startInclusive = 0L,
    endInclusive = (GENERIC_MEDIA_PROBE_BYTES - 1).toLong(),
)
internal val LIVE_MEDIA_RANGE = SourceByteRange(
    startInclusive = 0L,
    endInclusive = (LIVE_MEDIA_PROBE_BYTES - 1).toLong(),
)
internal val HITOMI_APP_FALLBACK_CASE = ProviderProbeCase(
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

internal fun String.isWebPUrl(): Boolean {
    return substringBefore('?').substringBefore('#').endsWith(".webp", ignoreCase = true)
}

internal fun String.isMp4Url(): Boolean {
    return substringBefore('?').substringBefore('#').endsWith(".mp4", ignoreCase = true)
}

internal fun ByteArray.isWebP(): Boolean {
    if (size < 12) return false
    return copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
        copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP"
}

internal fun ByteArray.hasMp4FtypBox(): Boolean {
    if (size < 8) return false
    return (0..size - 4).any { index ->
        this[index] == 'f'.code.toByte() &&
            this[index + 1] == 't'.code.toByte() &&
            this[index + 2] == 'y'.code.toByte() &&
            this[index + 3] == 'p'.code.toByte()
    }
}

internal object CancellingSourceHttpClient : SourceHttpClient {
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
