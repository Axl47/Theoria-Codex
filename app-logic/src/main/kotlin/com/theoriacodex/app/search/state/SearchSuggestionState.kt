package com.theoriacodex.app.search.state

import com.theoriacodex.app.search.SearchAutocompleteResult
import com.theoriacodex.domain.adapter.TagSuggestion

/** Pure state transitions for the two-stage local/remote suggestion pipeline. */
fun SearchUiState.withAutocompleteResult(result: SearchAutocompleteResult): SearchUiState = copy(
    query = query.copy(
        selectedScope = result.selectedScope,
        validationMessage = result.validationMessage,
    ),
    suggestions = suggestions.copy(
        input = result.input,
        autocomplete = result.autocomplete,
        facetedAutocomplete = result.facetedAutocomplete,
        canCommitInput = SearchDraftReducer.canCommitInput(
            copy(suggestions = suggestions.copy(autocomplete = result.autocomplete)),
            result.input,
        ),
    ),
)

fun SearchUiState.withTrendingSuggestions(trending: List<TagSuggestion>): SearchUiState =
    copy(suggestions = suggestions.copy(trending = trending))

fun SearchUiState.withSuggestionInput(input: String): SearchUiState = copy(
    suggestions = suggestions.copy(
        input = input,
        canCommitInput = SearchDraftReducer.canCommitInput(this, input),
    ),
)
