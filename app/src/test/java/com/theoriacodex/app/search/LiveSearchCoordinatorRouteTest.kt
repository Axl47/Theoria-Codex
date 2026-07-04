package com.theoriacodex.app.search

import com.theoriacodex.app.media.postDownloadMediaCandidate
import com.theoriacodex.app.source.exposedRealSources
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.RealAdapterRegistry
import com.theoriacodex.sources.credentials.GelbooruCredentials
import com.theoriacodex.sources.credentials.PixivAuthTokens
import com.theoriacodex.sources.credentials.Rule34XxxCredentials
import com.theoriacodex.sources.credentials.SourceCredentialsProvider
import com.theoriacodex.sources.health.ProviderProbeCase
import com.theoriacodex.sources.health.ProviderProbeCases
import com.theoriacodex.sources.http.DefaultSourceHttpClient
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LiveSearchCoordinatorRouteTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `live seeded searches route through search coordinator`() = runTest {
        assumeTrue("Live source route smoke is opt-in", System.getProperty("theoria.liveSources") == "true")

        val credentialedSources = credentialedSourcesFromEnvironment()
        val appExposedSources = exposedRealSources(
            rule34XxxConfigured = SourceKey.RULE34XXX in credentialedSources,
        )
        val cases = selectedProbeCases(
            credentialedSources = credentialedSources,
            appExposedSources = appExposedSources,
        )
        assumeTrue("No live source cases selected", cases.isNotEmpty())

        val registry = realRegistry(appExposedSources)
        val issues = mutableListOf<String>()

        cases.forEach { probeCase ->
            val coordinator = executeSeededSearch(registry, probeCase, issues) ?: return@forEach

            val prefix = probeCase.autocompletePrefix.orEmpty()
            if (prefix.isNotBlank()) {
                coordinator.refreshAutocompleteSuggestions(prefix)
                if (coordinator.autocompleteSuggestions.isEmpty()) {
                    issues += "${probeCase.source.name}: autocomplete returned no tags for prefix $prefix"
                }
            }

            coordinator.loadTrendingTags(forceRefresh = true)
            if (coordinator.trendingTags.isEmpty()) {
                issues += "${probeCase.source.name}: trending tags returned no tags"
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
        val cases = selectedProbeCases(
            credentialedSources = credentialedSources,
            appExposedSources = appExposedSources,
        ).filter { probeCase -> probeCase.mediaProbe }
        assumeTrue("No live source media cases selected", cases.isNotEmpty())

        val registry = realRegistry(appExposedSources)
        val issues = mutableListOf<String>()

        cases.forEach { probeCase ->
            val coordinator = executeSeededSearch(registry, probeCase, issues) ?: return@forEach
            val post = coordinator.results.firstOrNull()
            val candidate = post?.let(::postDownloadMediaCandidate)
            if (candidate == null) {
                issues += "${probeCase.source.name}: app media policy found no downloadable candidate"
                return@forEach
            }

            val probe = runCatching {
                probeMediaCandidate(
                    url = candidate.url,
                    headers = candidate.requestHeaders,
                )
            }
            val statusCode = probe.getOrNull()
            if (statusCode !in 200..399) {
                issues += "${probeCase.source.name}: media URL ${sanitizeUrlForReport(candidate.url)} returned ${statusCode ?: probe.exceptionOrNull()?.javaClass?.simpleName.orEmpty()}"
            }
        }

        assertTrue(issues.joinToString(separator = "\n"), issues.isEmpty())
    }

    private suspend fun executeSeededSearch(
        registry: RealAdapterRegistry,
        probeCase: ProviderProbeCase,
        issues: MutableList<String>,
    ): SearchCoordinator? {
        val coordinator = SearchCoordinator(
            registry = registry,
            tagSuggestionStore = FileBackedTagSuggestionStore(
                storeFile = File(tempFolder.newFolder(probeCase.source.name.lowercase()), "tag_suggestions.json"),
            ),
        )
        coordinator.initialize()
        val mode = QueryMode.Source(probeCase.source)
        val prepared = coordinator.prepareTagSearch(
            includeTags = probeCase.includeTags,
            mode = mode,
        )
        if (!prepared) {
            issues += "${probeCase.source.name}: could not prepare source-mode seeded search"
            return null
        }
        coordinator.setSort(probeCase.sort)
        coordinator.applyDraft()

        if (coordinator.errorMessage != null) {
            issues += "${probeCase.source.name}: search error ${coordinator.errorMessage}"
        }
        if (coordinator.results.isEmpty()) {
            issues += "${probeCase.source.name}: seeded search returned no posts for ${probeCase.includeTags.joinToString()}"
        }
        val failedStatuses = coordinator.statuses.filter { status ->
            status.errorMessage != null || status.failureReason != null
        }
        if (failedStatuses.isNotEmpty()) {
            issues += "${probeCase.source.name}: status failures ${failedStatuses.joinToString { it.errorMessage ?: it.failureReason?.name.orEmpty() }}"
        }
        return coordinator
    }

    private fun realRegistry(appExposedSources: Set<SourceKey>): RealAdapterRegistry {
        return RealAdapterRegistry(
            credentialsProvider = EnvironmentCredentialsProvider,
            httpClient = DefaultSourceHttpClient(
                connectTimeoutMs = 8_000,
                readTimeoutMs = 12_000,
                maxRetries = 0,
            ),
            exposedSources = appExposedSources,
        )
    }

    private suspend fun probeMediaCandidate(
        url: String,
        headers: Map<String, String>,
    ): Int = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 8_000
            connection.readTimeout = 12_000
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            (headers + ("Range" to "bytes=0-0")).forEach { (name, value) ->
                connection.setRequestProperty(name, value)
            }
            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..399) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            stream?.use { input -> input.read() }
            statusCode
        } finally {
            connection.disconnect()
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
        appExposedSources: Set<SourceKey>,
    ): List<ProviderProbeCase> {
        val requestedSources = System.getProperty("theoria.liveSources.sources")
            ?.split(',')
            ?.mapNotNull { raw -> runCatching { SourceKey.valueOf(raw.trim()) }.getOrNull() }
            ?.toSet()
        val configuredCases = System.getProperty("theoria.providerProbeCases")
            ?.takeIf(String::isNotBlank)
            ?.let { caseFile -> ProviderProbeCases.fromJson(File(caseFile).readText()) }
            ?: ProviderProbeCases.defaults
        return configuredCases.filter { probeCase ->
            val sourceSelected = if (requestedSources == null) {
                probeCase.source in appExposedSources
            } else {
                probeCase.source in requestedSources
            }
            sourceSelected &&
                probeCase.source in appExposedSources &&
                (!probeCase.requiresCredentials || probeCase.source in credentialedSources)
        }
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
