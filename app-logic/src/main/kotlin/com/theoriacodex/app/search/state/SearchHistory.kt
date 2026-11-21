package com.theoriacodex.app.search.state

import com.theoriacodex.domain.model.Query
import com.theoriacodex.data.repository.SearchScrollState

/** One settled search and, once it has been left, where its results were scrolled. Result pages are never kept. */
data class SearchHistoryEntry(
    val query: Query,
    val sourceScope: SearchSourceScope,
    val scrollState: SearchScrollState = SearchScrollState(0, 0),
) {
    /** Whether [other] is the same search, wherever either was scrolled. */
    fun sameSearch(other: SearchHistoryEntry?): Boolean =
        other != null && other.query == query && other.sourceScope == sourceScope
}

/**
 * The settled searches around [current], the last one settled: [back] were left before it, newest last, and
 * [forward] were stepped back from, nearest first. Live intermediates never enter it; a search enters only once
 * it settles. Swiping the collapsed search bar and "Undo last search" step through it.
 */
data class SearchQueryHistory(
    val back: List<SearchHistoryEntry> = emptyList(),
    val current: SearchHistoryEntry? = null,
    val forward: List<SearchHistoryEntry> = emptyList(),
) {
    /** Steps back are possible from [onScreen]: to an earlier search, or from an unsettled one to [current]. */
    fun canStepBack(onScreen: SearchHistoryEntry): Boolean =
        back.isNotEmpty() || (current != null && !onScreen.sameSearch(current))

    val canStepForward: Boolean get() = forward.isNotEmpty()

    /** [current] is being left for another search; [scrollState] is the place to return to. */
    fun leaving(scrollState: SearchScrollState): SearchQueryHistory =
        copy(current = current?.copy(scrollState = scrollState))

    /** [entry] settled. A different search moves [current] back, bounded to [LIMIT] steps, and drops the forward steps. */
    fun settled(entry: SearchHistoryEntry): SearchQueryHistory = when {
        current == null -> copy(current = entry)
        entry.sameSearch(current) -> this
        else -> SearchQueryHistory(back = (back + current).takeLast(LIMIT), current = entry)
    }

    /**
     * The search a step back from [onScreen] returns to, and the history after it. An unsettled search on screen
     * leaves no trace and returns to [current]; otherwise [onScreen], with its place, becomes the first forward step.
     */
    fun stepBack(onScreen: SearchHistoryEntry): SearchHistoryStep? {
        val settled = current ?: return null
        if (!onScreen.sameSearch(settled)) return SearchHistoryStep(settled, this)
        val target = back.lastOrNull() ?: return null
        return SearchHistoryStep(target, SearchQueryHistory(back.dropLast(1), target, listOf(onScreen) + forward))
    }

    /** The search a step forward from [onScreen] goes to; a settled [onScreen] is kept with its place. */
    fun stepForward(onScreen: SearchHistoryEntry): SearchHistoryStep? {
        val target = forward.firstOrNull() ?: return null
        val left = current?.let { settled -> if (onScreen.sameSearch(settled)) onScreen else settled }
        return SearchHistoryStep(target, SearchQueryHistory((back + listOfNotNull(left)).takeLast(LIMIT), target, forward.drop(1)))
    }

    companion object {
        const val LIMIT = 20
    }
}

/** Where a history step goes, with the scroll place to restore, and the history once it has gone there. */
data class SearchHistoryStep(val target: SearchHistoryEntry, val history: SearchQueryHistory)

/** The search whose results are on screen, scrolled to [scrollState]. */
fun SearchUiState.appliedHistoryEntry(scrollState: SearchScrollState = SearchScrollState(0, 0)): SearchHistoryEntry =
    SearchHistoryEntry(query.applied, query.appliedSourceScope, scrollState)
