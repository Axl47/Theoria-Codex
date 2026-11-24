package com.theoriacodex.app.search

import com.theoriacodex.app.feed.FeedCursor
import com.theoriacodex.app.search.state.SearchSourceScope
import com.theoriacodex.app.search.state.queryMode
import com.theoriacodex.app.search.state.sources
import com.theoriacodex.data.repository.SourceRuntimeSettings
import com.theoriacodex.data.repository.SourceWeightSettings
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.normalizedForMode
import com.theoriacodex.domain.query.QueryHash
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.SourceRunStatus
import com.theoriacodex.domain.orchestration.SourceRunState
import com.theoriacodex.domain.orchestration.UnifiedSearchResult
import com.theoriacodex.domain.adapter.FacetedSearchScope
import com.theoriacodex.domain.adapter.FacetedTagSuggestion
import com.theoriacodex.domain.adapter.TagSuggestion

fun searchExecutionKey(query: Query, sourceScope: SearchSourceScope): String {
    val queryHash = QueryHash.from(query.normalizedForMode(sourceScope.queryMode()).query)
    val temporary = sourceScope as? SearchSourceScope.Temporary ?: return queryHash
    return "$queryHash|temporary-sources:${temporary.sources.joinToString(",") { it.id }}"
}

interface SearchExecutionService {
    suspend fun executeInitial(
        query: Query,
        sourceScope: SearchSourceScope,
        sources: SearchSources,
        onPartial: (suspend (SearchExecutionResult.Success) -> Unit)? = null,
    ): SearchExecutionResult

    suspend fun executePage(continuation: SearchContinuation): SearchPageResult

    suspend fun retrySource(
        query: Query,
        sourceScope: SearchSourceScope,
        sources: SearchSources,
        previous: SearchContinuation?,
        source: SourceKey,
    ): SearchPageResult

    suspend fun cachedFirstPage(executionKey: String, sources: Set<SourceKey>): List<Post> = emptyList()

    suspend fun rememberFirstPage(executionKey: String, posts: List<Post>) = Unit

    suspend fun recordAcceptedSearch(result: SearchExecutionResult) = Unit

    suspend fun persistAppliedSearch(
        query: Query,
        sourceScope: SearchSourceScope,
        executionKey: String,
    )
}

data class SearchInitialization(
    val query: Query,
    val sourceScope: SearchSourceScope,
    val appliedByMode: Map<String, Query>,
    val availableSources: List<SourceKey>,
    val hasExecutedSearch: Boolean,
    val validationMessage: String? = null,
    val runtime: SourceRuntimeSettings = SourceRuntimeSettings(),
    val scopesBySource: Map<SourceKey, List<FacetedSearchScope>> = emptyMap(),
    val exactTagSources: Map<SourceKey, String> = emptyMap(),
)

data class SearchSources(
    val enabled: Set<SourceKey>,
    val weights: Map<SourceKey, Double>,
) {
    companion object {
        fun of(runtime: SourceRuntimeSettings, scope: SearchSourceScope, available: Set<SourceKey>): SearchSources {
            val enabled = scope.sources(runtime.enabledSources, available)
            return SearchSources(enabled, SourceWeightSettings.weightsFor(runtime, enabled))
        }
    }
}

data class SearchAutocompleteResult(
    val input: String,
    val selectedScope: FacetedSearchScope,
    val validationMessage: String? = null,
    val autocomplete: List<TagSuggestion> = emptyList(),
    val facetedAutocomplete: List<FacetedTagSuggestion> = emptyList(),
    val searchedTags: List<SearchTerm> = emptyList(),
)

data class SearchContinuation(
    val executionKey: String,
    val sourceScope: SearchSourceScope,
    val enabledSources: Set<SourceKey>,
    val availableSources: List<SourceKey>,
    val cursor: FeedCursor,
    val failedSources: Set<SourceKey> = emptySet(),
) {
    val query: Query
        get() = cursor.query

    val canLoadMore: Boolean
        get() = cursor.pageTokens.any { (source, token) -> source !in failedSources && !token.isNullOrBlank() }

    fun accept(page: UnifiedSearchResult, attempted: Set<SourceKey>): SearchContinuation = copy(
        cursor = cursor.accept(page, attempted),
        failedSources = failedSources - attempted +
            page.statuses.filter { it.state == SourceRunState.FAILED }.map { it.source },
    )
}

sealed interface SearchExecutionResult {
    val executionKey: String
    val query: Query
    val sourceScope: SearchSourceScope
    val statuses: List<SourceRunStatus>

    data class Success(
        override val executionKey: String,
        override val query: Query,
        override val sourceScope: SearchSourceScope,
        val posts: List<Post>,
        override val statuses: List<SourceRunStatus>,
        val continuation: SearchContinuation,
    ) : SearchExecutionResult

    data class Failure(
        override val executionKey: String,
        override val query: Query,
        override val sourceScope: SearchSourceScope,
        override val statuses: List<SourceRunStatus>,
        val message: String,
        val continuation: SearchContinuation? = null,
    ) : SearchExecutionResult
}

val SearchExecutionResult.completedSuccessfully: Boolean
    get() = this is SearchExecutionResult.Success && statuses.any { it.state == SourceRunState.SUCCESS }

sealed interface SearchPageResult {
    val executionKey: String
    val statuses: List<SourceRunStatus>

    data class Success(
        override val executionKey: String,
        val posts: List<Post>,
        override val statuses: List<SourceRunStatus>,
        val continuation: SearchContinuation,
    ) : SearchPageResult

    data class Failure(
        override val executionKey: String,
        override val statuses: List<SourceRunStatus>,
        val message: String,
    ) : SearchPageResult
}

fun com.theoriacodex.app.search.state.SearchUiState.admits(requestId: Long, executionKey: String): Boolean =
    execution.request?.let { request -> request.id == requestId && request.executionKey == executionKey } == true

fun com.theoriacodex.app.search.state.SearchUiState.showsPartialResults(
    requestId: Long, result: SearchExecutionResult.Success,
): Boolean = admits(requestId, result.executionKey) &&
    execution.request?.cachedFirstPage == com.theoriacodex.app.search.state.CachedFirstPage.NONE
