package com.theoriacodex.sources.health

import com.theoriacodex.domain.adapter.QuickQueryKind
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import kotlin.system.measureTimeMillis

enum class ProviderHealthStatus {
    OK,
    DEGRADED,
    FAILED,
    SKIPPED,
}

data class ProviderHealthCheckResult(
    val source: SourceKey,
    val checkName: String,
    val status: ProviderHealthStatus,
    val latencyMs: Long,
    val failureReason: SourceFailureReason? = null,
    val message: String? = null,
    val checkedAtEpochMs: Long,
)

data class ProviderHealthReport(
    val liveProvidersEnabled: Boolean,
    val generatedAtEpochMs: Long,
    val results: List<ProviderHealthCheckResult>,
    val probeResults: List<ProviderProbeStepResult> = emptyList(),
)

data class ProviderProbeCase(
    val source: SourceKey,
    val includeTags: List<String> = emptyList(),
    val sort: SortMode = SortMode.NEWEST,
    val autocompletePrefix: String? = null,
    val strictTagEcho: Boolean = false,
    val requiresCredentials: Boolean = false,
    val mediaProbe: Boolean = true,
)

data class ProviderProbeStepResult(
    val source: SourceKey,
    val checkName: String,
    val status: ProviderHealthStatus,
    val latencyMs: Long,
    val failureReason: SourceFailureReason? = null,
    val message: String? = null,
    val checkedAtEpochMs: Long,
    val includeTags: List<String> = emptyList(),
    val autocompletePrefix: String? = null,
    val itemCount: Int? = null,
    val samplePostId: String? = null,
)

object ProviderProbeCases {
    val defaults: List<ProviderProbeCase> = listOf(
        ProviderProbeCase(
            source = SourceKey.PIXIV,
            includeTags = listOf("landscape"),
            autocompletePrefix = "land",
            requiresCredentials = true,
        ),
        ProviderProbeCase(
            source = SourceKey.GELBOORU,
            includeTags = listOf("landscape"),
            autocompletePrefix = "land",
            strictTagEcho = true,
            requiresCredentials = true,
        ),
        ProviderProbeCase(
            source = SourceKey.AIBOORU,
            includeTags = listOf("1girl"),
            autocompletePrefix = "1g",
            strictTagEcho = true,
        ),
        ProviderProbeCase(
            source = SourceKey.NHENTAI,
            includeTags = listOf("english"),
            autocompletePrefix = "eng",
        ),
        ProviderProbeCase(
            source = SourceKey.IWARA,
            includeTags = listOf("3d"),
            autocompletePrefix = "3d",
        ),
        ProviderProbeCase(
            source = SourceKey.RULE34XXX,
            includeTags = listOf("landscape"),
            autocompletePrefix = "land",
            requiresCredentials = true,
        ),
        ProviderProbeCase(
            source = SourceKey.RULE34PAHEAL,
            includeTags = listOf("genshin_impact"),
            autocompletePrefix = "gensh",
        ),
        ProviderProbeCase(
            source = SourceKey.RULE34VIDEO,
            includeTags = listOf("animation"),
            autocompletePrefix = "anim",
        ),
        ProviderProbeCase(
            source = SourceKey.RULE34GEN,
            includeTags = listOf("animation"),
            autocompletePrefix = "anim",
        ),
    )

    fun fromJson(json: String): List<ProviderProbeCase> {
        val parsed = com.google.gson.Gson().fromJson(json, Array<ProviderProbeCaseRecord>::class.java)
        return parsed.map { record ->
            ProviderProbeCase(
                source = SourceKey.valueOf(record.source),
                includeTags = record.includeTags.orEmpty(),
                sort = record.sort?.let(SortMode::valueOf) ?: SortMode.NEWEST,
                autocompletePrefix = record.autocompletePrefix,
                strictTagEcho = record.strictTagEcho ?: false,
                requiresCredentials = record.requiresCredentials ?: false,
                mediaProbe = record.mediaProbe ?: true,
            )
        }
    }

    private data class ProviderProbeCaseRecord(
        val source: String,
        val includeTags: List<String>? = null,
        val sort: String? = null,
        val autocompletePrefix: String? = null,
        val strictTagEcho: Boolean? = null,
        val requiresCredentials: Boolean? = null,
        val mediaProbe: Boolean? = null,
    )
}

class ProviderHealthChecker(
    private val registry: SourceAdapterRegistry,
    private val nowProvider: () -> Long = System::currentTimeMillis,
) {
    suspend fun checkAll(sources: Collection<SourceKey> = registry.availableSources()): List<ProviderHealthCheckResult> {
        return sources.sortedBy { it.name }.map { source ->
            val adapter = registry.adapterFor(source)
            if (adapter == null) {
                ProviderHealthCheckResult(
                    source = source,
                    checkName = CHECK_NAME,
                    status = ProviderHealthStatus.SKIPPED,
                    latencyMs = 0L,
                    message = "Source is not exposed by this registry",
                    checkedAtEpochMs = nowProvider(),
                )
            } else {
                check(adapter)
            }
        }
    }

    private suspend fun check(adapter: SourceAdapter): ProviderHealthCheckResult {
        var failure: Throwable? = null
        var itemCount = 0
        val latency = measureTimeMillis {
            try {
                val query = adapter.quickQuery(QuickQueryKind.NEWEST)
                val page = adapter.search(query, pageToken = null)
                itemCount = page.items.size
            } catch (error: Throwable) {
                failure = error
            }
        }
        val checkedAt = nowProvider()
        val caught = failure
        if (caught != null) {
            val typed = caught as? SourceAdapterException
            return ProviderHealthCheckResult(
                source = adapter.sourceKey,
                checkName = CHECK_NAME,
                status = ProviderHealthStatus.FAILED,
                latencyMs = latency,
                failureReason = typed?.reason,
                message = caught.message ?: caught::class.simpleName,
                checkedAtEpochMs = checkedAt,
            )
        }
        return ProviderHealthCheckResult(
            source = adapter.sourceKey,
            checkName = CHECK_NAME,
            status = if (itemCount > 0) ProviderHealthStatus.OK else ProviderHealthStatus.DEGRADED,
            latencyMs = latency,
            message = if (itemCount > 0) "Returned $itemCount posts" else "Reachable but returned no posts",
            checkedAtEpochMs = checkedAt,
        )
    }

    private companion object {
        const val CHECK_NAME = "newest-search"
    }
}

class ProviderProbeRunner(
    private val registry: SourceAdapterRegistry,
    private val nowProvider: () -> Long = System::currentTimeMillis,
    private val credentialedSources: Set<SourceKey> = emptySet(),
) {
    suspend fun runAll(cases: List<ProviderProbeCase> = ProviderProbeCases.defaults): List<ProviderProbeStepResult> {
        return cases.sortedBy { it.source.name }.flatMap { probeCase ->
            runCase(probeCase)
        }
    }

    private suspend fun runCase(probeCase: ProviderProbeCase): List<ProviderProbeStepResult> {
        val adapter = registry.adapterFor(probeCase.source)
            ?: return listOf(
                skipped(
                    probeCase = probeCase,
                    checkName = "source-exposure",
                    message = "Source is not exposed by this registry",
                )
            )

        if (probeCase.requiresCredentials && probeCase.source !in credentialedSources) {
            return listOf(
                skipped(
                    probeCase = probeCase,
                    checkName = "credentials",
                    message = "Source requires credentials and none were provided",
                )
            )
        }

        val results = mutableListOf<ProviderProbeStepResult>()
        results += runStep(probeCase = probeCase, checkName = "newest-search") {
            val query = adapter.quickQuery(QuickQueryKind.NEWEST)
            val page = adapter.search(query, pageToken = null)
            val sample = page.items.firstOrNull()
            okOrDegraded(
                probeCase = probeCase,
                checkName = "newest-search",
                itemCount = page.items.size,
                samplePost = sample,
                okMessage = "Returned ${page.items.size} posts",
                emptyMessage = "Reachable but returned no newest posts",
            )
        }

        var seededSample: Post? = null
        results += runStep(probeCase = probeCase, checkName = "seeded-search") {
            val query = Query(
                mode = QueryMode.Source(probeCase.source),
                includeTags = probeCase.includeTags,
                excludeTags = emptyList(),
                sort = probeCase.sort,
                dateRange = null,
                minScore = null,
            )
            val page = adapter.search(query, pageToken = null)
            seededSample = page.items.firstOrNull()
            val tagEchoMatches = !probeCase.strictTagEcho || page.items.any { post ->
                val tags = post.canonicalTags + post.rawTags
                probeCase.includeTags.all { expected ->
                    tags.any { tag -> tagsMatch(tag, expected) }
                }
            }
            when {
                page.items.isEmpty() -> degraded(
                    probeCase = probeCase,
                    checkName = "seeded-search",
                    itemCount = 0,
                    message = "Reachable but returned no posts for ${probeCase.includeTags.joinToString()}",
                )
                !tagEchoMatches -> degraded(
                    probeCase = probeCase,
                    checkName = "seeded-search",
                    itemCount = page.items.size,
                    samplePost = seededSample,
                    message = "Returned posts, but none echoed required tags ${probeCase.includeTags.joinToString()}",
                )
                else -> ok(
                    probeCase = probeCase,
                    checkName = "seeded-search",
                    itemCount = page.items.size,
                    samplePost = seededSample,
                    message = "Returned ${page.items.size} seeded posts",
                )
            }
        }

        results += runAutocomplete(probeCase, adapter)
        results += runStep(probeCase = probeCase, checkName = "trending-tags") {
            val tags = adapter.trendingTags(limit = 5)
            okOrDegraded(
                probeCase = probeCase,
                checkName = "trending-tags",
                itemCount = tags.size,
                okMessage = "Returned ${tags.size} trending tags",
                emptyMessage = "Reachable but returned no trending tags",
            )
        }

        val sample = seededSample
        results += if (sample == null) {
            skipped(
                probeCase = probeCase,
                checkName = "resolve-post",
                message = "No seeded search sample post to resolve",
            )
        } else {
            runStep(probeCase = probeCase, checkName = "resolve-post") {
                val resolved = adapter.resolvePost(sample.id)
                if (resolved == null) {
                    degraded(
                        probeCase = probeCase,
                        checkName = "resolve-post",
                        samplePost = sample,
                        message = "Resolve returned null for ${sample.id.sourcePostId}",
                    )
                } else {
                    ok(
                        probeCase = probeCase,
                        checkName = "resolve-post",
                        samplePost = resolved,
                        message = "Resolved ${resolved.id.sourcePostId}",
                    )
                }
            }
        }

        if (probeCase.mediaProbe) {
            results += if (sample == null) {
                skipped(
                    probeCase = probeCase,
                    checkName = "media-metadata",
                    message = "No seeded search sample post for media metadata check",
                )
            } else {
                val mediaUrls = mediaUrls(sample)
                if (mediaUrls.isEmpty()) {
                    degraded(
                        probeCase = probeCase,
                        checkName = "media-metadata",
                        samplePost = sample,
                        message = "Sample post has no media URLs",
                    )
                } else {
                    ok(
                        probeCase = probeCase,
                        checkName = "media-metadata",
                        itemCount = mediaUrls.size,
                        samplePost = sample,
                        message = "Sample post exposes ${mediaUrls.size} media URLs",
                    )
                }
            }
        }

        return results
    }

    private suspend fun runAutocomplete(
        probeCase: ProviderProbeCase,
        adapter: SourceAdapter,
    ): ProviderProbeStepResult {
        val prefix = probeCase.autocompletePrefix?.trim().orEmpty()
        if (prefix.isBlank()) {
            return skipped(
                probeCase = probeCase,
                checkName = "autocomplete-tags",
                message = "No autocomplete prefix configured",
            )
        }
        return runStep(probeCase = probeCase, checkName = "autocomplete-tags") {
            val tags = adapter.autocompleteTags(prefix = prefix, limit = 5)
            val prefixMatches = tags.any { suggestion -> tagsMatch(suggestion.text, prefix) }
            when {
                tags.isEmpty() -> degraded(
                    probeCase = probeCase,
                    checkName = "autocomplete-tags",
                    autocompletePrefix = prefix,
                    itemCount = 0,
                    message = "Reachable but returned no autocomplete tags",
                )
                !prefixMatches -> degraded(
                    probeCase = probeCase,
                    checkName = "autocomplete-tags",
                    autocompletePrefix = prefix,
                    itemCount = tags.size,
                    message = "Returned tags, but none matched prefix $prefix",
                )
                else -> ok(
                    probeCase = probeCase,
                    checkName = "autocomplete-tags",
                    autocompletePrefix = prefix,
                    itemCount = tags.size,
                    message = "Returned ${tags.size} autocomplete tags",
                )
            }
        }
    }

    private suspend fun runStep(
        probeCase: ProviderProbeCase,
        checkName: String,
        block: suspend () -> ProviderProbeStepResult,
    ): ProviderProbeStepResult {
        var result: ProviderProbeStepResult? = null
        var failure: Throwable? = null
        val latency = measureTimeMillis {
            try {
                result = block()
            } catch (error: Throwable) {
                failure = error
            }
        }
        val caught = failure
        if (caught != null) {
            val typed = caught as? SourceAdapterException
            return ProviderProbeStepResult(
                source = probeCase.source,
                checkName = checkName,
                status = ProviderHealthStatus.FAILED,
                latencyMs = latency,
                failureReason = typed?.reason,
                message = caught.message ?: caught::class.simpleName,
                checkedAtEpochMs = nowProvider(),
                includeTags = probeCase.includeTags,
                autocompletePrefix = probeCase.autocompletePrefix,
            )
        }
        return requireNotNull(result).copy(latencyMs = latency)
    }

    private fun okOrDegraded(
        probeCase: ProviderProbeCase,
        checkName: String,
        itemCount: Int,
        samplePost: Post? = null,
        okMessage: String,
        emptyMessage: String,
    ): ProviderProbeStepResult {
        return if (itemCount > 0) {
            ok(probeCase, checkName, itemCount, samplePost, message = okMessage)
        } else {
            degraded(probeCase, checkName, itemCount, samplePost, message = emptyMessage)
        }
    }

    private fun ok(
        probeCase: ProviderProbeCase,
        checkName: String,
        itemCount: Int? = null,
        samplePost: Post? = null,
        autocompletePrefix: String? = probeCase.autocompletePrefix,
        message: String,
    ): ProviderProbeStepResult {
        return result(
            probeCase = probeCase,
            checkName = checkName,
            status = ProviderHealthStatus.OK,
            itemCount = itemCount,
            samplePost = samplePost,
            autocompletePrefix = autocompletePrefix,
            message = message,
        )
    }

    private fun degraded(
        probeCase: ProviderProbeCase,
        checkName: String,
        itemCount: Int? = null,
        samplePost: Post? = null,
        autocompletePrefix: String? = probeCase.autocompletePrefix,
        message: String,
    ): ProviderProbeStepResult {
        return result(
            probeCase = probeCase,
            checkName = checkName,
            status = ProviderHealthStatus.DEGRADED,
            itemCount = itemCount,
            samplePost = samplePost,
            autocompletePrefix = autocompletePrefix,
            message = message,
        )
    }

    private fun skipped(
        probeCase: ProviderProbeCase,
        checkName: String,
        message: String,
    ): ProviderProbeStepResult {
        return result(
            probeCase = probeCase,
            checkName = checkName,
            status = ProviderHealthStatus.SKIPPED,
            message = message,
        )
    }

    private fun result(
        probeCase: ProviderProbeCase,
        checkName: String,
        status: ProviderHealthStatus,
        itemCount: Int? = null,
        samplePost: Post? = null,
        autocompletePrefix: String? = probeCase.autocompletePrefix,
        message: String,
    ): ProviderProbeStepResult {
        return ProviderProbeStepResult(
            source = probeCase.source,
            checkName = checkName,
            status = status,
            latencyMs = 0L,
            message = message,
            checkedAtEpochMs = nowProvider(),
            includeTags = probeCase.includeTags,
            autocompletePrefix = autocompletePrefix,
            itemCount = itemCount,
            samplePostId = samplePost?.id?.sourcePostId,
        )
    }

    private fun mediaUrls(post: Post): List<String> {
        return buildList {
            post.preview.url?.takeIf(String::isNotBlank)?.let(::add)
            post.full?.url?.takeIf(String::isNotBlank)?.let(::add)
            post.media.mapNotNullTo(this) { media -> media.url?.takeIf(String::isNotBlank) }
        }.distinct()
    }

    private fun tagsMatch(actual: String, expected: String): Boolean {
        val normalizedActual = actual.normalizeTag()
        val normalizedExpected = expected.normalizeTag()
        return normalizedActual == normalizedExpected ||
            normalizedActual.contains(normalizedExpected) ||
            normalizedExpected.contains(normalizedActual)
    }

    private fun String.normalizeTag(): String {
        return trim()
            .lowercase()
            .replace('_', ' ')
            .replace('-', ' ')
    }
}
