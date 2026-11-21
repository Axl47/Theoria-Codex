package com.theoriacodex.app.feed

import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.SourceRunState
import com.theoriacodex.domain.orchestration.SourceWeightNormalization
import com.theoriacodex.domain.orchestration.UnifiedSearchOrchestrator
import com.theoriacodex.domain.orchestration.UnifiedSearchResult

/**
 * Where a multi-source feed stands: the base query, the query each source actually ran, the blend weights and each
 * source's next-page token. Search and For You page through it the same way: [nextPage], then [accept].
 */
data class FeedCursor(
    val query: Query,
    val queriesBySource: Map<SourceKey, Query> = emptyMap(),
    val weights: Map<SourceKey, Double> = emptyMap(),
    /** Membership admits a request: a source with a null token retries its first page after a root failure. */
    val pageTokens: Map<SourceKey, String?> = emptyMap(),
) {
    /**
     * Folds in a page fetched from [attempted], adopting the queries the sources ran. A source that answered keeps its
     * next token or leaves at its end, an excluded one leaves, and a failed one keeps its token for a retry.
     */
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

/** Fetches [cursor]'s next page from [sources], blending them by their share of the cursor's weights. */
suspend fun UnifiedSearchOrchestrator.nextPage(cursor: FeedCursor, sources: Set<SourceKey>): UnifiedSearchResult = search(
    query = cursor.query,
    enabledSources = sources,
    pageTokens = cursor.pageTokens.filterKeys { it in sources },
    weights = SourceWeightNormalization.normalize(sources, cursor.weights),
    queryOverridesBySource = cursor.queriesBySource.filterKeys { it in sources },
)
