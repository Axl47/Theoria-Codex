package com.theoriacodex.domain.orchestration

import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.query.CapabilityExclusionReason
import com.theoriacodex.domain.query.SourceCapabilityGate
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

enum class SourceRunState {
    SUCCESS,
    EXCLUDED,
    FAILED,
}

data class SourceRunStatus(
    val source: SourceKey,
    val state: SourceRunState,
    val exclusionReasons: Set<com.theoriacodex.domain.query.CapabilityExclusionReason> = emptySet(),
    val failureReason: SourceFailureReason? = null,
    val errorMessage: String? = null,
)

data class UnifiedSearchResult(
    val items: List<Post>,
    val nextPageTokens: Map<SourceKey, String?>,
    val statuses: List<SourceRunStatus>,
)

class UnifiedSearchOrchestrator(
    private val adaptersBySource: Map<SourceKey, SourceAdapter>,
) {
    suspend fun search(
        query: Query,
        enabledSources: Set<SourceKey>,
        pageTokens: Map<SourceKey, String?>,
        weights: Map<SourceKey, Double>,
        queryOverridesBySource: Map<SourceKey, Query> = emptyMap(),
    ): UnifiedSearchResult = coroutineScope {
        val portableQuery = query.portableTermsForUnified()
        val candidateAdapters = enabledSources.mapNotNull { source ->
            adaptersBySource[source]?.let { source to it }
        }.toMap()

        val excluded = SourceCapabilityGate.excludedSources(
            query = portableQuery,
            capabilitiesBySource = candidateAdapters.mapValues { it.value.capabilities },
        )
        val clientSideExcludeSources = excluded
            .filterValues { reasons ->
                reasons.isNotEmpty() &&
                    reasons.all { reason -> reason == CapabilityExclusionReason.EXCLUDE_TAGS_UNSUPPORTED }
            }
            .keys
        val hardExcluded = excluded
            .filterValues { reasons ->
                reasons.any { reason -> reason != CapabilityExclusionReason.EXCLUDE_TAGS_UNSUPPORTED }
            }

        val jobs = candidateAdapters
            .filterKeys { source -> source !in hardExcluded }
            .map { (source, adapter) ->
                async {
                    val sourceBaseQuery = (queryOverridesBySource[source] ?: portableQuery)
                        .let { override ->
                            if (query.mode == QueryMode.Unified) {
                                override.copy(
                                    includeTerms = override.includeTerms.filter { it.isPortableGeneralTag },
                                    excludeTerms = override.excludeTerms.filter { it.isPortableGeneralTag },
                                )
                            } else {
                                override
                            }
                        }
                    val sourceQuery = if (source in clientSideExcludeSources) {
                        sourceBaseQuery.copy(excludeTerms = emptyList())
                    } else {
                        sourceBaseQuery
                    }
                    source to runCatching {
                        adapter.search(sourceQuery, pageTokens[source]).let { page ->
                            if (source in clientSideExcludeSources) {
                                page.copy(
                                    items = applyClientSideExcludeFilter(
                                        posts = page.items,
                                        excludeTags = sourceBaseQuery.excludeTags,
                                    )
                                )
                            } else {
                                page
                            }
                        }
                    }
                }
            }
            .awaitAll()

        val succeededPages = mutableMapOf<SourceKey, Page<Post>>()
        val statuses = mutableListOf<SourceRunStatus>()

        hardExcluded.forEach { (source, reasons) ->
            statuses += SourceRunStatus(
                source = source,
                state = SourceRunState.EXCLUDED,
                exclusionReasons = reasons,
            )
        }

        jobs.forEach { (source, result) ->
            result.fold(
                onSuccess = { page ->
                    succeededPages[source] = page
                    statuses += SourceRunStatus(source = source, state = SourceRunState.SUCCESS)
                },
                onFailure = { error ->
                    statuses += SourceRunStatus(
                        source = source,
                        state = SourceRunState.FAILED,
                        failureReason = mapFailureReason(error),
                        errorMessage = error.message,
                    )
                }
            )
        }

        val interleaved = weightedInterleave(
            pagesBySource = succeededPages,
            weightsBySource = weights,
        )

        UnifiedSearchResult(
            items = interleaved,
            nextPageTokens = succeededPages.mapValues { it.value.nextPageToken },
            statuses = statuses.sortedBy { it.source.name },
        )
    }

    private fun weightedInterleave(
        pagesBySource: Map<SourceKey, Page<Post>>,
        weightsBySource: Map<SourceKey, Double>,
    ): List<Post> {
        val queues = pagesBySource.mapValues { (_, page) -> ArrayDeque(page.items) }.toMutableMap()
        val sources = queues.keys.toList().sortedBy { it.name }
        if (sources.isEmpty()) return emptyList()

        val normalizedWeights = SourceWeightNormalization.normalize(sources, weightsBySource)
        val accumulators = sources.associateWith { 0.0 }.toMutableMap()
        val merged = mutableListOf<Post>()

        while (queues.values.any { it.isNotEmpty() }) {
            var chosen: SourceKey? = null
            var bestScore = Double.NEGATIVE_INFINITY

            sources.forEach { source ->
                if (queues[source].orEmpty().isNotEmpty()) {
                    val score = accumulators.getValue(source) + normalizedWeights.getValue(source)
                    accumulators[source] = score
                    if (score > bestScore) {
                        bestScore = score
                        chosen = source
                    }
                }
            }

            val selected = chosen ?: break
            val queue = queues.getValue(selected)
            merged += queue.removeFirst()
            accumulators[selected] = accumulators.getValue(selected) - 1.0
        }

        return merged
    }

    private fun mapFailureReason(error: Throwable): SourceFailureReason {
        return when (error) {
            is SourceAdapterException -> error.reason
            else -> SourceFailureReason.UNKNOWN
        }
    }

    private fun applyClientSideExcludeFilter(
        posts: List<Post>,
        excludeTags: List<String>,
    ): List<Post> {
        val normalizedExcluded = excludeTags
            .asSequence()
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .toSet()
        if (normalizedExcluded.isEmpty()) return posts
        return posts.filterNot { post ->
            val postTags = (post.canonicalTags + post.rawTags)
                .asSequence()
                .map { it.trim().lowercase().removePrefix("-") }
                .filter { it.isNotBlank() }
            postTags.any { it in normalizedExcluded }
        }
    }
}
