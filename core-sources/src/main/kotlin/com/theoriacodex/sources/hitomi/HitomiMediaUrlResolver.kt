package com.theoriacodex.sources.hitomi

import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.media.mimeFromFileExt
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class HitomiMediaFormat {
    AVIF,
    WEBP,
    ORIGINAL,
}

data class HitomiMediaFile(
    val hash: String,
    val name: String,
    val hasAvif: Boolean,
)

data class HitomiCdnConfiguration(
    val basePath: String,
    val version: String,
    val shardTwoKeys: Set<Int>,
)

data class HitomiMediaCandidate(
    val url: String,
    val mime: String,
    val format: HitomiMediaFormat,
    val shard: Int,
    val isAlternateShard: Boolean,
    val configurationBasePath: String,
    val configurationVersion: String,
)

/**
 * Resolves Hitomi file hashes through the provider's mutable `gg.js` configuration.
 *
 * Every format includes the configured primary shard followed by the alternate shard. A caller
 * that receives a media 404 can pass the failed candidate's [HitomiMediaCandidate.configurationVersion]
 * to [refreshCandidates]. That method performs at most one refresh for the failed version, even
 * when the provider returns the same configuration again.
 */
class HitomiMediaUrlResolver(
    private val httpClient: SourceHttpClient,
    private val cacheTtlMillis: Long = DEFAULT_CONFIGURATION_TTL_MILLIS,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val configurationUrl: String = GG_CONFIGURATION_URL,
) {
    private data class CachedConfiguration(
        val configuration: HitomiCdnConfiguration,
        val loadedAtMillis: Long,
    )

    private val configurationMutex = Mutex()
    private val cachedConfiguration = AtomicReference<CachedConfiguration?>(null)
    private val invalidationGeneration = AtomicLong(0L)
    private val refreshedFailureVersions = ConcurrentHashMap.newKeySet<String>()

    init {
        require(cacheTtlMillis > 0L) { "Hitomi CDN configuration cache TTL must be positive" }
    }

    suspend fun candidates(
        file: HitomiMediaFile,
        refresh: Boolean = false,
    ): List<HitomiMediaCandidate> {
        val configuration = configuration(forceRefresh = refresh)
        return buildCandidates(file, configuration)
    }

    suspend fun refresh(): HitomiCdnConfiguration = configuration(forceRefresh = true)

    /**
     * Refreshes once for [failedConfigurationVersion] and derives a new ordered candidate list.
     * If another request already refreshed that version, the current cached configuration is used.
     * A provider or parse failure consumes the attempt; cancellation does not.
     */
    suspend fun refreshCandidates(
        file: HitomiMediaFile,
        failedConfigurationVersion: String,
    ): List<HitomiMediaCandidate> {
        val normalizedFailedVersion = failedConfigurationVersion.trim()
        if (normalizedFailedVersion.isEmpty()) {
            throw HitomiProtocolException("Failed Hitomi CDN configuration version must not be blank")
        }

        val configuration = configurationMutex.withLock {
            val current = cachedConfiguration.get()?.configuration
            if (current != null && current.version != normalizedFailedVersion) {
                return@withLock current
            }
            if (!refreshedFailureVersions.add(normalizedFailedVersion)) {
                return@withLock current ?: throw HitomiProtocolException(
                    "Hitomi CDN configuration refresh was already attempted for version " +
                        normalizedFailedVersion,
                )
            }

            try {
                fetchAndCacheConfiguration()
            } catch (error: CancellationException) {
                refreshedFailureVersions.remove(normalizedFailedVersion)
                throw error
            }
        }
        return buildCandidates(file, configuration)
    }

    /** Clears both the cached configuration and the per-version 404 refresh guard. */
    fun invalidate() {
        invalidationGeneration.incrementAndGet()
        cachedConfiguration.set(null)
        refreshedFailureVersions.clear()
    }

    private suspend fun configuration(forceRefresh: Boolean): HitomiCdnConfiguration {
        if (!forceRefresh) {
            freshCachedConfiguration()?.let { return it }
        }
        return configurationMutex.withLock {
            if (!forceRefresh) {
                freshCachedConfiguration()?.let { return@withLock it }
            }
            fetchAndCacheConfiguration()
        }
    }

    private fun freshCachedConfiguration(): HitomiCdnConfiguration? {
        val cached = cachedConfiguration.get() ?: return null
        val ageMillis = nowMillis() - cached.loadedAtMillis
        return cached.configuration.takeIf { ageMillis in 0 until cacheTtlMillis }
    }

    private suspend fun fetchAndCacheConfiguration(): HitomiCdnConfiguration {
        val generationAtStart = invalidationGeneration.get()
        val response = httpClient.get(
            url = configurationUrl,
            headers = GG_REQUEST_HEADERS,
        )
        if (response.statusCode !in 200..299) {
            throw HitomiProtocolException(
                "Hitomi CDN configuration request failed ($configurationUrl, HTTP ${response.statusCode})",
            )
        }
        val parsed = parseConfiguration(response.body)
        if (invalidationGeneration.get() == generationAtStart) {
            cachedConfiguration.set(
                CachedConfiguration(
                    configuration = parsed,
                    loadedAtMillis = nowMillis(),
                ),
            )
        }
        return parsed
    }

    companion object {
        const val GG_CONFIGURATION_URL: String = "${HitomiProtocol.DATA_BASE_URL}/gg.js"
        const val DEFAULT_CONFIGURATION_TTL_MILLIS: Long = 30L * 60L * 1_000L
        const val MAX_CONFIGURATION_RESPONSE_CHARS: Int = 512 * 1_024

        private const val CDN_DOMAIN = "gold-usergeneratedcontent.net"

        private val GG_REQUEST_HEADERS = mapOf(
            "Accept" to "application/javascript, text/javascript, */*;q=0.8",
            "Referer" to "https://hitomi.la/",
            "User-Agent" to "Mozilla/5.0",
        )
        private val BASE_PATH_ASSIGNMENT = Regex("""\bb\s*:\s*['\"]([^'\"]+)['\"]""")
        private val SHARD_FUNCTION = Regex(
            pattern = """\bm\s*:\s*function\s*\([^)]*\)\s*\{(.*?)\}\s*,\s*s\s*:""",
            option = RegexOption.DOT_MATCHES_ALL,
        )
        private val SHARD_CASE = Regex("""\bcase\s+(\d+)\s*:""")
        private val SHARD_ONE_ASSIGNMENT = Regex("""\bo\s*=\s*1\s*;\s*break\s*;?""")
        private val SAFE_BASE_PATH = Regex("""(?:[A-Za-z0-9][A-Za-z0-9._-]*/)+""")
        private val FILE_HASH = Regex("""[0-9a-f]{64}""")
        private val FILE_EXTENSION = Regex("""[A-Za-z0-9]{1,10}""")

        fun parseConfiguration(script: String): HitomiCdnConfiguration {
            if (script.length > MAX_CONFIGURATION_RESPONSE_CHARS) {
                throw HitomiProtocolException("Hitomi gg.js response exceeded the size limit")
            }
            val basePath = BASE_PATH_ASSIGNMENT.find(script)?.groupValues?.get(1)?.trim()
                ?: throw HitomiProtocolException("Hitomi gg.js did not declare a CDN base path")
            if (!SAFE_BASE_PATH.matches(basePath) || ".." in basePath) {
                throw HitomiProtocolException("Hitomi gg.js declared an unsafe CDN base path")
            }

            val shardBody = SHARD_FUNCTION.find(script)?.groupValues?.get(1)
                ?: throw HitomiProtocolException("Hitomi gg.js did not declare the shard function")
            if (!SHARD_ONE_ASSIGNMENT.containsMatchIn(shardBody)) {
                throw HitomiProtocolException("Hitomi gg.js shard function used an unsupported shape")
            }
            val shardTwoKeys = SHARD_CASE.findAll(shardBody)
                .map { match ->
                    match.groupValues[1].toIntOrNull()
                        ?: throw HitomiProtocolException("Hitomi gg.js contained an invalid shard key")
                }
                .toSet()
            if (shardTwoKeys.isEmpty()) {
                throw HitomiProtocolException("Hitomi gg.js did not contain any shard routing keys")
            }

            return HitomiCdnConfiguration(
                basePath = basePath,
                version = basePath.removeSuffix("/"),
                shardTwoKeys = shardTwoKeys,
            )
        }

        private fun buildCandidates(
            file: HitomiMediaFile,
            configuration: HitomiCdnConfiguration,
        ): List<HitomiMediaCandidate> {
            val hash = file.hash.trim().lowercase(Locale.ROOT)
            if (!FILE_HASH.matches(hash)) {
                throw HitomiProtocolException("Hitomi media file hash must contain 64 hexadecimal characters")
            }
            val extension = file.name
                .substringAfterLast('.', missingDelimiterValue = "")
                .trim()
                .lowercase(Locale.ROOT)
            if (!FILE_EXTENSION.matches(extension)) {
                throw HitomiProtocolException("Hitomi media file name did not contain a safe extension")
            }

            val routingKey = hashRoutingKey(hash)
            val primaryShard = if (routingKey in configuration.shardTwoKeys) 2 else 1
            val shards = listOf(primaryShard, 3 - primaryShard)
            val pathNumber = routingKey
            val formats = buildList {
                if (file.hasAvif) add(HitomiMediaFormat.AVIF)
                add(HitomiMediaFormat.WEBP)
                add(HitomiMediaFormat.ORIGINAL)
            }

            return formats.flatMap { format ->
                shards.mapIndexed { index, shard ->
                    val (host, path, mime) = when (format) {
                        HitomiMediaFormat.AVIF -> Triple(
                            "a$shard.$CDN_DOMAIN",
                            "${configuration.basePath}$pathNumber/$hash.avif",
                            "image/avif",
                        )

                        HitomiMediaFormat.WEBP -> Triple(
                            "w$shard.$CDN_DOMAIN",
                            "${configuration.basePath}$pathNumber/$hash.webp",
                            "image/webp",
                        )

                        HitomiMediaFormat.ORIGINAL -> Triple(
                            "$shard.$CDN_DOMAIN",
                            "images/${configuration.basePath}$pathNumber/$hash.$extension",
                            mimeFromFileExt(extension) ?: "application/octet-stream",
                        )
                    }
                    HitomiMediaCandidate(
                        url = "https://$host/$path",
                        mime = mime,
                        format = format,
                        shard = shard,
                        isAlternateShard = index == 1,
                        configurationBasePath = configuration.basePath,
                        configurationVersion = configuration.version,
                    )
                }
            }
        }

        private fun hashRoutingKey(hash: String): Int {
            val trailing = hash.takeLast(3)
            return (trailing.takeLast(1) + trailing.take(2)).toInt(radix = 16)
        }
    }
}
