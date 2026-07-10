package com.theoriacodex.sources.health

import com.google.gson.GsonBuilder
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.RealAdapterRegistry
import com.theoriacodex.sources.credentials.GelbooruCredentials
import com.theoriacodex.sources.credentials.PixivAuthTokens
import com.theoriacodex.sources.credentials.Rule34XxxCredentials
import com.theoriacodex.sources.credentials.SourceCredentialsProvider
import com.theoriacodex.sources.http.DefaultSourceHttpClient
import java.io.File
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) = runBlocking {
    val outputFile = args.firstOrNull()
        ?.let(::File)
        ?: File("build/reports/provider-health/provider-health.json")
    val liveEnabled = System.getProperty("theoria.liveProviders") == "true"
    val strictMode = System.getProperty("theoria.liveSources.strict") == "true"
    val gson = GsonBuilder().setPrettyPrinting().create()
    val report = if (liveEnabled) {
        val requestedSources = parseRequestedSources(System.getProperty("theoria.liveSources.sources"))
        val httpClient = DefaultSourceHttpClient(
            connectTimeoutMs = 8_000,
            readTimeoutMs = 12_000,
            maxRetries = 0,
        )
        val registry = RealAdapterRegistry(
            credentialsProvider = EnvironmentCredentialsProvider,
            httpClient = httpClient,
            exposedSources = requestedSources.ifEmpty { SourceKey.entries.toSet() },
        )
        val probeCases = selectProbeCases(loadProbeCases(), requestedSources)
        val genericResults = ProviderProbeRunner(
            registry = registry,
            credentialedSources = credentialedSourcesFromEnvironment(),
        ).runAll(probeCases)
        val hitomiResults = if (probeCases.any { probeCase -> probeCase.source == SourceKey.HITOMI }) {
            val adapter = requireNotNull(registry.adapterFor(SourceKey.HITOMI)) {
                "Hitomi was selected for provider health but is not exposed by the registry"
            }
            HitomiProviderHealthProbe(adapter = adapter, httpClient = httpClient).runAll()
        } else {
            emptyList()
        }
        val probeResults = genericResults + hitomiResults
        ProviderHealthReport(
            liveProvidersEnabled = true,
            generatedAtEpochMs = System.currentTimeMillis(),
            results = aggregateProbeResults(probeResults),
            probeResults = probeResults,
        )
    } else {
        ProviderHealthReport(
            liveProvidersEnabled = false,
            generatedAtEpochMs = System.currentTimeMillis(),
            results = emptyList(),
        )
    }

    outputFile.parentFile?.mkdirs()
    outputFile.writeText(gson.toJson(report))
    printSummary(report, outputFile)
    val requestedSources = parseRequestedSources(System.getProperty("theoria.liveSources.sources"))
    if (liveEnabled && strictMode && shouldFailStrict(report, requestedSources)) {
        error("Strict live source health failed. See report: ${outputFile.absolutePath}")
    }
}

internal fun parseRequestedSources(raw: String?): Set<SourceKey> {
    if (raw.isNullOrBlank()) return emptySet()
    return raw.split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map { name ->
            runCatching { SourceKey.valueOf(name.uppercase()) }
                .getOrElse { throw IllegalArgumentException("Unknown live source filter: $name") }
        }
        .toSet()
}

internal fun selectProbeCases(
    cases: List<ProviderProbeCase>,
    requestedSources: Set<SourceKey>,
): List<ProviderProbeCase> {
    if (requestedSources.isEmpty()) return cases
    val selected = cases.filter { probeCase -> probeCase.source in requestedSources }
    val missing = requestedSources - selected.mapTo(mutableSetOf(), ProviderProbeCase::source)
    require(missing.isEmpty()) {
        "No provider probe case is configured for ${missing.joinToString { it.name }}"
    }
    return selected
}

internal fun shouldFailStrict(
    report: ProviderHealthReport,
    requestedSources: Set<SourceKey>,
): Boolean {
    if (requestedSources.isEmpty()) {
        return report.probeResults.any { result -> result.status == ProviderHealthStatus.FAILED }
    }
    return requestedSources.any { source ->
        val sourceResults = report.probeResults.filter { result -> result.source == source }
        sourceResults.isEmpty() || sourceResults.any { result -> result.status != ProviderHealthStatus.OK }
    }
}

private fun loadProbeCases(): List<ProviderProbeCase> {
    val caseFile = System.getProperty("theoria.providerProbeCases")
        ?.takeIf(String::isNotBlank)
        ?.let(::File)
    if (caseFile == null) return ProviderProbeCases.defaults
    return ProviderProbeCases.fromJson(caseFile.readText())
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

private fun aggregateProbeResults(probeResults: List<ProviderProbeStepResult>): List<ProviderHealthCheckResult> {
    return probeResults
        .groupBy { it.source }
        .toSortedMap(compareBy { it.name })
        .map { (source, sourceResults) ->
            val status = when {
                sourceResults.any { it.status == ProviderHealthStatus.FAILED } -> ProviderHealthStatus.FAILED
                sourceResults.any { it.status == ProviderHealthStatus.DEGRADED } -> ProviderHealthStatus.DEGRADED
                sourceResults.all { it.status == ProviderHealthStatus.SKIPPED } -> ProviderHealthStatus.SKIPPED
                else -> ProviderHealthStatus.OK
            }
            val firstProblem = sourceResults.firstOrNull {
                it.status == ProviderHealthStatus.FAILED || it.status == ProviderHealthStatus.DEGRADED
            } ?: sourceResults.firstOrNull()
            ProviderHealthCheckResult(
                source = source,
                checkName = "source-probe",
                status = status,
                latencyMs = sourceResults.sumOf { it.latencyMs },
                failureReason = firstProblem?.failureReason,
                message = sourceResults
                    .groupingBy { it.status }
                    .eachCount()
                    .entries
                    .joinToString(", ") { (stepStatus, count) -> "${stepStatus.name.lowercase()}=$count" }
                    .ifBlank { firstProblem?.message.orEmpty() },
                checkedAtEpochMs = firstProblem?.checkedAtEpochMs ?: System.currentTimeMillis(),
            )
        }
}

private fun printSummary(report: ProviderHealthReport, outputFile: File) {
    if (!report.liveProvidersEnabled) {
        println("Provider health skipped. Re-run with -Ptheoria.liveProviders=true to perform live checks.")
        println("Report: ${outputFile.absolutePath}")
        return
    }
    val grouped = report.results.groupingBy { it.status }.eachCount()
    val summary = ProviderHealthStatus.entries.joinToString(", ") { status ->
        "${status.name.lowercase()}=${grouped[status] ?: 0}"
    }
    println("Provider health: $summary")
    report.results.forEach { result ->
        val suffix = listOfNotNull(
            "${result.latencyMs}ms",
            result.failureReason?.name,
            result.message,
        ).joinToString(" | ")
        println("${result.source.name}: ${result.status.name}${if (suffix.isBlank()) "" else " ($suffix)"}")
    }
    if (report.probeResults.isNotEmpty()) {
        println("Provider probe steps:")
        report.probeResults.forEach { result ->
            val suffix = listOfNotNull(
                "${result.latencyMs}ms",
                result.failureReason?.name,
                result.requestUrl,
                result.message,
            ).joinToString(" | ")
            println("${result.source.name}/${result.checkName}: ${result.status.name}${if (suffix.isBlank()) "" else " ($suffix)"}")
        }
    }
    println("Report: ${outputFile.absolutePath}")
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
