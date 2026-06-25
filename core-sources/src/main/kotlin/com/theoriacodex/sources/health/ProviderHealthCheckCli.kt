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
    val gson = GsonBuilder().setPrettyPrinting().create()
    val report = if (liveEnabled) {
        val registry = RealAdapterRegistry(
            credentialsProvider = EnvironmentCredentialsProvider,
            httpClient = DefaultSourceHttpClient(connectTimeoutMs = 8_000, readTimeoutMs = 12_000, maxRetries = 0),
            exposedSources = SourceKey.entries.toSet(),
        )
        ProviderHealthReport(
            liveProvidersEnabled = true,
            generatedAtEpochMs = System.currentTimeMillis(),
            results = ProviderHealthChecker(registry).checkAll(SourceKey.entries),
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
