package com.theoriacodex.domain.orchestration

import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SourceKey
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
    ): UnifiedSearchResult = coroutineScope {
        val candidateAdapters = enabledSources.mapNotNull { source ->
            adaptersBySource[source]?.let { source to it }
        }.toMap()

        val excluded = SourceCapabilityGate.excludedSources(
            query = query,
            capabilitiesBySource = candidateAdapters.mapValues { it.value.capabilities },
        )

        val jobs = candidateAdapters
            .filterKeys { source -> source !in excluded }
            .map { (source, adapter) ->
                async {
                    source to runCatching { adapter.search(query, pageTokens[source]) }
                }
            }
            .awaitAll()

        val succeededPages = mutableMapOf<SourceKey, Page<Post>>()
        val statuses = mutableListOf<SourceRunStatus>()

        excluded.forEach { (source, reasons) ->
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

        val normalizedWeights = normalizeWeights(sources, weightsBySource)
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

    private fun normalizeWeights(
        sources: List<SourceKey>,
        weightsBySource: Map<SourceKey, Double>,
    ): Map<SourceKey, Double> {
        val raw = sources.associateWith { source ->
            val weight = weightsBySource[source] ?: 1.0
            if (weight <= 0.0) 1.0 else weight
        }
        val total = raw.values.sum().takeIf { it > 0.0 } ?: sources.size.toDouble()
        return raw.mapValues { (_, weight) -> weight / total }
    }

    private fun mapFailureReason(error: Throwable): SourceFailureReason {
        return when (error) {
            is SourceAdapterException -> error.reason
            else -> SourceFailureReason.UNKNOWN
        }
    }
}
