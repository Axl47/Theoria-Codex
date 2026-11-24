package com.theoriacodex.app.feed

import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.SourceRunState
import com.theoriacodex.domain.orchestration.SourceWeightNormalization
import com.theoriacodex.domain.orchestration.UnifiedSearchOrchestrator
import com.theoriacodex.domain.orchestration.UnifiedSearchResult

data class FeedCursor(
    val query: Query,
    val queriesBySource: Map<SourceKey, Query> = emptyMap(),
    val weights: Map<SourceKey, Double> = emptyMap(),
    val pageTokens: Map<SourceKey, String?> = emptyMap(),
) {
    fun accept(page: UnifiedSearchResult, attempted: Set<SourceKey>): FeedCursor {
        val bySource = page.statuses.associateBy { it.source }
        val tokens = pageTokens.toMutableMap()
        attempted.forEach { source ->
            when (bySource[source]?.state) {
                SourceRunState.SUCCESS -> {
                    val token = page.nextPageTokens[source]?.takeIf(String::isNotBlank)
                    if (token == null) tokens.remove(source) else tokens[source] = token
                }
                SourceRunState.EXCLUDED -> tokens.remove(source)
                SourceRunState.FAILED, null -> Unit
            }
        }
        return copy(queriesBySource = queriesBySource + page.queriesBySource, pageTokens = tokens)
    }
}

suspend fun UnifiedSearchOrchestrator.nextPage(cursor: FeedCursor, sources: Set<SourceKey>): UnifiedSearchResult = search(
    query = cursor.query,
    enabledSources = sources,
    pageTokens = cursor.pageTokens.filterKeys { it in sources },
    weights = SourceWeightNormalization.normalize(sources, cursor.weights),
    queryOverridesBySource = cursor.queriesBySource.filterKeys { it in sources },
)
