package com.theoriacodex.app.search

import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.SourceRunStatus
import com.theoriacodex.domain.adapter.FacetedSearchScope
import com.theoriacodex.domain.adapter.FacetedTagSuggestion
import com.theoriacodex.domain.adapter.TagSuggestion

interface SearchExecutionService {
    fun executionKeyFor(
        query: Query,
        sourceScope: com.theoriacodex.app.search.state.SearchSourceScope,
    ): String

    suspend fun executeInitial(
        query: Query,
        sourceScope: com.theoriacodex.app.search.state.SearchSourceScope,
    ): SearchExecutionResult

    suspend fun executePage(continuation: SearchContinuation): SearchPageResult

    suspend fun persistAppliedSearch(
        query: Query,
        sourceScope: com.theoriacodex.app.search.state.SearchSourceScope,
        executionKey: String,
    )
}

data class SearchInitialization(
    val query: Query,
    val sourceScope: com.theoriacodex.app.search.state.SearchSourceScope,
    val appliedByMode: Map<String, Query>,
    val availableSources: List<SourceKey>,
    val hasExecutedSearch: Boolean,
    val validationMessage: String? = null,
)

data class SearchAutocompleteResult(
    val input: String,
    val selectedScope: FacetedSearchScope,
    val validationMessage: String? = null,
    val autocomplete: List<TagSuggestion> = emptyList(),
    val facetedAutocomplete: List<FacetedTagSuggestion> = emptyList(),
)

data class SearchEnvironmentChange(
    val settingsChanged: Boolean,
    val sourcesChanged: Boolean,
    val availableSources: List<SourceKey>,
)

/** Immutable provider continuation owned by the Search route owner. */
data class SearchContinuation(
    val executionKey: String,
    val query: Query,
    val sourceScope: com.theoriacodex.app.search.state.SearchSourceScope,
    val enabledSources: Set<SourceKey>,
    val availableSources: List<SourceKey>,
    val weights: Map<SourceKey, Double>,
    val unifiedPageTokens: Map<SourceKey, String?> = emptyMap(),
    val unifiedQueryOverrides: Map<SourceKey, Query> = emptyMap(),
    val sourcePageToken: String? = null,
) {
    val canLoadMore: Boolean
        get() = sourcePageToken?.isNotBlank() == true ||
            unifiedPageTokens.values.any { token -> !token.isNullOrBlank() }
}

sealed interface SearchExecutionResult {
    val executionKey: String
    val query: Query
    val sourceScope: com.theoriacodex.app.search.state.SearchSourceScope
    val statuses: List<SourceRunStatus>

    data class Success(
        override val executionKey: String,
        override val query: Query,
        override val sourceScope: com.theoriacodex.app.search.state.SearchSourceScope,
        val posts: List<Post>,
        override val statuses: List<SourceRunStatus>,
        val continuation: SearchContinuation,
    ) : SearchExecutionResult

    data class Failure(
        override val executionKey: String,
        override val query: Query,
        override val sourceScope: com.theoriacodex.app.search.state.SearchSourceScope,
        override val statuses: List<SourceRunStatus>,
        val message: String,
    ) : SearchExecutionResult
}

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
