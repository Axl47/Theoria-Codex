package com.theoriacodex.app.search.state

import com.theoriacodex.app.search.SearchCoordinator
import com.theoriacodex.domain.adapter.FacetedSearchScope
import com.theoriacodex.domain.adapter.FacetedTagSuggestion
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.SourceRunStatus

/** Immutable read model captured from the current coordinator boundary. */
data class SearchCoordinatorSnapshot(
    val draftQuery: Query,
    val appliedQuery: Query,
    val appliedQueryHash: String,
    val results: List<Post>,
    val statuses: List<SourceRunStatus>,
    val trendingTags: List<TagSuggestion>,
    val autocompleteSuggestions: List<TagSuggestion>,
    val facetedAutocompleteSuggestions: List<FacetedTagSuggestion>,
    val selectedSearchScope: FacetedSearchScope,
    val supportedSearchScopes: List<FacetedSearchScope>,
    val availableSources: List<SourceKey>,
    val modeOptions: List<QueryMode>,
    val enabledSourceCount: Int,
    val tagInputValidationMessage: String?,
    val loading: Boolean,
    val loadingMore: Boolean,
    val canLoadMore: Boolean,
    val errorMessage: String?,
    val displayResultsVersion: Int,
    val hasAnySearchRun: Boolean,
)

fun SearchCoordinator.captureSearchCoordinatorSnapshot(): SearchCoordinatorSnapshot {
    return SearchCoordinatorSnapshot(
        draftQuery = draftQuery,
        appliedQuery = appliedQuery,
        appliedQueryHash = appliedQueryHash,
        results = displayResults(),
        statuses = statuses,
        trendingTags = trendingTags,
        autocompleteSuggestions = autocompleteSuggestions,
        facetedAutocompleteSuggestions = facetedAutocompleteSuggestions,
        selectedSearchScope = selectedSearchScope,
        supportedSearchScopes = supportedSearchScopes,
        availableSources = availableSources,
        modeOptions = modeOptions,
        enabledSourceCount = enabledSourceCount,
        tagInputValidationMessage = tagInputValidationMessage,
        loading = loading,
        loadingMore = loadingMore,
        canLoadMore = canLoadMore,
        errorMessage = errorMessage,
        displayResultsVersion = displayResultsVersion,
        hasAnySearchRun = hasAnySearchRun,
    )
}

fun SearchCoordinator.toSearchUiState(
    restoration: SearchRestorationUiState = SearchRestorationUiState.Restored(restoredQuery = false),
    executionOverride: SearchExecutionUiState? = null,
): SearchUiState {
    return captureSearchCoordinatorSnapshot().toSearchUiState(
        restoration = restoration,
        executionOverride = executionOverride,
    )
}

fun SearchCoordinatorSnapshot.toSearchUiState(
    restoration: SearchRestorationUiState = SearchRestorationUiState.Restored(restoredQuery = false),
    executionOverride: SearchExecutionUiState? = null,
): SearchUiState {
    val inferredKind = when {
        loadingMore -> SearchRequestKind.PAGE
        loading && hasAnySearchRun -> SearchRequestKind.REPLACE
        loading -> SearchRequestKind.INITIAL
        else -> null
    }
    return SearchUiState(
        query = SearchQueryUiState(
            draft = draftQuery,
            applied = appliedQuery,
            appliedQueryHash = appliedQueryHash,
            availableSources = availableSources.toList(),
            modeOptions = modeOptions.toList(),
            enabledSourceCount = enabledSourceCount,
            supportedScopes = supportedSearchScopes.toList(),
            selectedScope = selectedSearchScope,
            validationMessage = tagInputValidationMessage?.takeIf(String::isNotBlank),
        ),
        content = SearchContentUiState(
            results = results.toList(),
            statuses = statuses.toList(),
            canLoadMore = canLoadMore,
            hasExecutedSearch = hasAnySearchRun,
            error = errorMessage?.takeIf(String::isNotBlank)?.let { message ->
                SearchErrorUiState(
                    message = message,
                    requestKind = inferredKind,
                    retryable = true,
                )
            },
            displayVersion = displayResultsVersion,
        ),
        suggestions = SearchSuggestionsUiState(
            trending = trendingTags.toList(),
            autocomplete = autocompleteSuggestions.toList(),
            facetedAutocomplete = facetedAutocompleteSuggestions.toList(),
        ),
        execution = executionOverride ?: SearchExecutionUiState(activeKind = inferredKind),
        restoration = restoration,
    )
}
