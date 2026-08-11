package com.theoriacodex.domain.orchestration

import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.Post
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
                                override.withIncludeTermGroups(
                                    override.effectiveIncludeTermGroups.filter { group ->
                                        group.isPortableGeneralTagGroup
                                    },
                                ).copy(
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
                    source to runCatchingPreservingCancellation {
                        searchSource(adapter, sourceQuery, pageTokens[source]).let { page ->
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
        val pages = branchQueries.mapIndexed { index, branchQuery ->
            val branchToken = incomingTokens[index]
            if (!initial && branchToken == null) {
                Page(items = emptyList(), nextPageToken = null)
            } else {
                adapter.search(branchQuery, branchToken)
            }
        }
        val visibleByBranch = pages.map { page ->
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
    }
}
