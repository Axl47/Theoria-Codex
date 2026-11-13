package com.theoriacodex.domain.orchestration

import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.DateRange
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.distinctByFile
import com.theoriacodex.domain.model.normalizedForMode
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchTermGroup
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.query.matchesIncludeTermGroups
import java.nio.charset.StandardCharsets
import java.util.Base64
import com.theoriacodex.domain.query.CapabilityExclusionReason
import com.theoriacodex.domain.query.SourceCapabilityGate
import com.theoriacodex.domain.query.withinTermLimit
import com.theoriacodex.domain.coroutines.mapConcurrent
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

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
    val queriesBySource: Map<SourceKey, Query> = emptyMap(),
)

class UnifiedSearchOrchestrator(
    private val adaptersBySource: Map<SourceKey, SourceAdapter>,
    private val sourceTimeoutMs: Long = 15_000L,
) {
    suspend fun search(
        query: Query,
        enabledSources: Set<SourceKey>,
        pageTokens: Map<SourceKey, String?>,
        weights: Map<SourceKey, Double>,
        queryOverridesBySource: Map<SourceKey, Query> = emptyMap(),
        prepareQuery: suspend (SourceKey, Query) -> Query = { _, sourceQuery -> sourceQuery },
        /** When set, receives usable results before every source has answered; see [publishAsSourcesAnswer]. */
        onPartial: (suspend (UnifiedSearchResult) -> Unit)? = null,
    ): UnifiedSearchResult = coroutineScope {
        val preparedQueries = ConcurrentHashMap<SourceKey, Query>()
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

        val answered = MutableStateFlow(emptyList<SourceAnswer>())
        val jobs = candidateAdapters
            .filterKeys { source -> source !in hardExcluded }
            .map { (source, adapter) ->
                async {
                    // A source with a term cap receives only the leading terms it accepts.
                    val sourceBaseQuery = sourceBaseQuery(query, portableQuery, queryOverridesBySource[source])
                        .withinTermLimit(adapter.capabilities.searchTermLimit)
                    val answer = source to runCatchingPreservingCancellation {
                        searchPreparedSource(
                            source, adapter, sourceBaseQuery, source in clientSideExcludeSources,
                            pageTokens[source], prepareQuery, preparedQueries,
                        )
                    }
                    answered.update { it + answer }
                    answer
                }
            }

        if (onPartial == null) {
            combineResults(jobs.awaitAll(), hardExcluded, weights, preparedQueries)
        } else {
            publishAsSourcesAnswer(answered, jobs.size, hardExcluded, weights, preparedQueries, onPartial)
        }
    }

    /**
     * Publishes the first sources to answer, then appends each later source below them, so one slow or dead source
     * cannot hold back results that already arrived, and cards already on screen never move. Sources answering within
     * [FIRST_ANSWERS_SETTLE_MS] of the first success are interleaved exactly as a complete search would be.
     */
    private suspend fun publishAsSourcesAnswer(
        answered: StateFlow<List<SourceAnswer>>,
        expected: Int,
        hardExcluded: Map<SourceKey, Set<CapabilityExclusionReason>>,
        weights: Map<SourceKey, Double>,
        preparedQueries: Map<SourceKey, Query>,
        onPartial: suspend (UnifiedSearchResult) -> Unit,
    ): UnifiedSearchResult {
        answered.first { answers -> answers.size == expected || answers.any { (_, result) -> result.isSuccess } }
        withTimeoutOrNull(FIRST_ANSWERS_SETTLE_MS) { answered.first { answers -> answers.size == expected } }

        var included = answered.value
        var combined = combineResults(included, hardExcluded, weights, preparedQueries)
        while (included.size < expected) {
            if (combined.statuses.any { it.state == SourceRunState.SUCCESS }) onPartial(combined)
            val latest = answered.first { answers -> answers.size > included.size }
            val lateItems = latest.drop(included.size).flatMap { (_, result) -> result.getOrNull()?.items.orEmpty() }
            combined = combineResults(
                latest, hardExcluded, weights, preparedQueries, items = (combined.items + lateItems).distinctByFile(),
            )
            included = latest
        }
        return combined
    }

    /** Combines [jobs] into one result; [items] replaces the weighted interleave when order is already fixed. */
    private fun combineResults(
        jobs: List<SourceAnswer>,
        hardExcluded: Map<SourceKey, Set<CapabilityExclusionReason>>,
        weights: Map<SourceKey, Double>,
        preparedQueries: Map<SourceKey, Query>,
        items: List<Post>? = null,
    ): UnifiedSearchResult {
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

        return UnifiedSearchResult(
            items = items ?: weightedInterleave(pagesBySource = succeededPages, weightsBySource = weights),
            nextPageTokens = succeededPages.mapValues { it.value.nextPageToken },
            statuses = statuses.sortedBy { it.source.id },
            queriesBySource = preparedQueries.toMap(),
        )
    }

    private fun sourceBaseQuery(query: Query, portableQuery: Query, override: Query?): Query {
        val base = override ?: portableQuery
        return if (query.mode == QueryMode.Unified) {
            base.normalizedForMode(QueryMode.Unified).query.copy(mode = base.mode)
        } else base
    }

    private suspend fun searchPreparedSource(
        source: SourceKey,
        adapter: SourceAdapter,
        sourceBaseQuery: Query,
        clientSideExclude: Boolean,
        pageToken: String?,
        prepareQuery: suspend (SourceKey, Query) -> Query,
        preparedQueries: MutableMap<SourceKey, Query>,
    ): Page<Post> {
        val sourceQuery = if (clientSideExclude) {
            sourceBaseQuery.copy(excludeTerms = emptyList())
        } else sourceBaseQuery
        return withTimeoutOrNull(sourceTimeoutMs) {
            val prepared = prepareQuery(source, sourceQuery)
            preparedQueries[source] = if (clientSideExclude) {
                prepared.copy(excludeTerms = sourceBaseQuery.excludeTerms)
            } else prepared
            val page = searchSource(adapter, prepared, pageToken).inDateRange(sourceBaseQuery.dateRange)
            if (clientSideExclude) {
                val hydrated = listOf(page).withSearchMetadata(adapter).single()
                hydrated.copy(items = applyClientSideExcludeFilter(hydrated.items, sourceBaseQuery.excludeTags))
            } else page
        } ?: throw SourceAdapterException(SourceFailureReason.NETWORK, "Source timed out")
    }

    suspend fun searchSource(
        adapter: SourceAdapter,
        query: Query,
        pageToken: String?,
    ): Page<Post> {
        val groups = query.effectiveIncludeTermGroups
        val alternatives = groups.filter { group -> group.terms.size > 1 }
        if (alternatives.isEmpty() || adapter.capabilities.supportsGroupedIncludeTagsServerSide) {
            return adapter.search(query, pageToken)
        }
        val pivot = alternatives.minBy { group -> group.terms.size }
        require(pivot.terms.size <= MAX_FALLBACK_BRANCHES) {
            "A tag group may contain at most $MAX_FALLBACK_BRANCHES alternatives for this source"
        }
        val singletonGroups = groups.filter { group -> group.terms.size == 1 }
        val branchQueries = pivot.terms.map { alternative ->
            query.withIncludeTermGroups(singletonGroups + SearchTermGroup.single(alternative))
        }
        val initial = pageToken == null
        val incomingTokens = if (initial) {
            List(branchQueries.size) { null }
        } else {
            decodeGroupedPageToken(pageToken, branchQueries.size)
        }
        val pages = branchQueries.withIndex().mapConcurrent(concurrency = 2) { (index, branchQuery) ->
            val branchToken = incomingTokens[index]
            if (!initial && branchToken == null) {
                Page(items = emptyList(), nextPageToken = null)
            } else {
                adapter.search(branchQuery, branchToken)
            }
        }
        val visibleByBranch = pages.withSearchMetadata(adapter).map { page ->
            page.items.filter { post -> post.matchesIncludeTermGroups(query) }
        }
        val merged = mergeBranches(visibleByBranch, query.sort)
        val nextTokens = pages.map(Page<Post>::nextPageToken)
        return Page(
            items = merged,
            nextPageToken = nextTokens.takeIf { tokens -> tokens.any { it != null } }
                ?.let(::encodeGroupedPageToken),
        )
    }

    private fun weightedInterleave(
        pagesBySource: Map<SourceKey, Page<Post>>,
        weightsBySource: Map<SourceKey, Double>,
    ): List<Post> {
        val queues = pagesBySource.mapValues { (_, page) -> ArrayDeque(page.items) }.toMutableMap()
        val sources = queues.keys.toList().sortedBy { it.id }
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

        // Sites that mirror each other list the same files; the copy from the earlier interleaved source stays.
        return merged.distinctByFile()
    }

    private fun mergeBranches(
        branches: List<List<Post>>,
        sort: SortMode,
    ): List<Post> {
        if (sort == SortMode.NEWEST) {
            return branches.flatten()
                .distinctBy(Post::id)
                .sortedWith(compareByDescending<Post> { post -> post.createdAtEpochMs ?: Long.MIN_VALUE })
        }
        val queues = branches.map(::ArrayDeque)
        val merged = mutableListOf<Post>()
        val seen = mutableSetOf<com.theoriacodex.domain.model.PostId>()
        while (queues.any { queue -> queue.isNotEmpty() }) {
            queues.forEach { queue ->
                while (queue.isNotEmpty()) {
                    val candidate = queue.removeFirst()
                    if (seen.add(candidate.id)) {
                        merged += candidate
                        break
                    }
                }
            }
        }
        return merged
    }

    private fun encodeGroupedPageToken(tokens: List<String?>): String {
        val encoded = tokens.joinToString(".") { token ->
            token?.let { value ->
                Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(value.toByteArray(StandardCharsets.UTF_8))
            } ?: NULL_BRANCH_TOKEN
        }
        return GROUPED_TOKEN_PREFIX + encoded
    }

    private fun decodeGroupedPageToken(token: String, expectedBranches: Int): List<String?> {
        require(token.startsWith(GROUPED_TOKEN_PREFIX)) { "Invalid grouped Search continuation" }
        val encoded = token.removePrefix(GROUPED_TOKEN_PREFIX).split('.')
        require(encoded.size == expectedBranches) { "Grouped Search continuation has the wrong branch count" }
        return encoded.map { item ->
            if (item == NULL_BRANCH_TOKEN) {
                null
            } else {
                String(Base64.getUrlDecoder().decode(item), StandardCharsets.UTF_8)
            }
        }
    }

    private fun mapFailureReason(error: Throwable): SourceFailureReason {
        return when (error) {
            is SourceAdapterException -> error.reason
            else -> SourceFailureReason.UNKNOWN
        }
    }

    /**
     * Drops posts outside the search's dates before sites are merged. Most sites ignore the range, and merging keeps
     * one copy of each file: an out-of-range copy kept there would hide the in-range copy another site holds.
     */
    private fun Page<Post>.inDateRange(range: DateRange?): Page<Post> =
        if (range == null) this else copy(items = items.filter { post -> post.createdAtEpochMs in range })

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

    private companion object {
        const val MAX_FALLBACK_BRANCHES = 8
        const val GROUPED_TOKEN_PREFIX = "theoria-group-v1:"
        const val NULL_BRANCH_TOKEN = "~"
        const val FIRST_ANSWERS_SETTLE_MS = 400L
    }
}

private typealias SourceAnswer = Pair<SourceKey, Result<Page<Post>>>
