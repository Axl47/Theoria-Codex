package com.theoriacodex.app.search.state

import com.theoriacodex.domain.model.Query
import com.theoriacodex.data.repository.SearchScrollState

data class SearchHistoryEntry(
    val query: Query,
    val sourceScope: SearchSourceScope,
    val scrollState: SearchScrollState = SearchScrollState(0, 0),
) {
    fun sameSearch(other: SearchHistoryEntry?): Boolean =
        other != null && other.query == query && other.sourceScope == sourceScope
}

data class SearchQueryHistory(
    val back: List<SearchHistoryEntry> = emptyList(),
    val current: SearchHistoryEntry? = null,
    val forward: List<SearchHistoryEntry> = emptyList(),
) {
    fun canStepBack(onScreen: SearchHistoryEntry): Boolean =
        back.isNotEmpty() || (current != null && !onScreen.sameSearch(current))

    val canStepForward: Boolean get() = forward.isNotEmpty()

    fun leaving(scrollState: SearchScrollState): SearchQueryHistory =
        copy(current = current?.copy(scrollState = scrollState))

    fun settled(entry: SearchHistoryEntry): SearchQueryHistory = when {
        current == null -> copy(current = entry)
        entry.sameSearch(current) -> this
        else -> SearchQueryHistory(back = (back + current).takeLast(LIMIT), current = entry)
    }

    fun stepBack(onScreen: SearchHistoryEntry): SearchHistoryStep? {
        val settled = current ?: return null
        if (!onScreen.sameSearch(settled)) return SearchHistoryStep(settled, this)
        val target = back.lastOrNull() ?: return null
        return SearchHistoryStep(target, SearchQueryHistory(back.dropLast(1), target, listOf(onScreen) + forward))
    }

    fun stepForward(onScreen: SearchHistoryEntry): SearchHistoryStep? {
        val target = forward.firstOrNull() ?: return null
        val left = current?.let { settled -> if (onScreen.sameSearch(settled)) onScreen else settled }
        return SearchHistoryStep(target, SearchQueryHistory((back + listOfNotNull(left)).takeLast(LIMIT), target, forward.drop(1)))
    }

    companion object {
        const val LIMIT = 20
    }
}

data class SearchHistoryStep(val target: SearchHistoryEntry, val history: SearchQueryHistory)

fun SearchUiState.appliedHistoryEntry(scrollState: SearchScrollState = SearchScrollState(0, 0)): SearchHistoryEntry =
    SearchHistoryEntry(query.applied, query.appliedSourceScope, scrollState)
