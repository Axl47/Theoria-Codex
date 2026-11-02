package com.theoriacodex.app.search

import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SortMode

/** The search bar's one line: the first tags, how many more there are, and active filters. */
internal fun collapsedSearchContextSummary(
    query: Query,
    activeFilterCount: Int,
): String? {
    val terms = query.effectiveIncludeTermGroups.flatMap { it.terms }.map { it.value } +
        query.excludeTerms.map { "-${it.value}" }
    val tags = terms.take(MAX_SUMMARY_TAGS).joinToString(", ").takeIf(String::isNotBlank)?.let { shown ->
        val hidden = terms.size - MAX_SUMMARY_TAGS
        if (hidden > 0) "$shown +$hidden" else shown
    }
    val filters = "$activeFilterCount ${if (activeFilterCount == 1) "filter" else "filters"}"
        .takeIf { activeFilterCount > 0 }
    return listOfNotNull(tags, filters).joinToString(" · ").takeIf(String::isNotBlank)
}

private const val MAX_SUMMARY_TAGS = 2

internal fun activeSearchFilterCount(
    appliedQuery: Query,
    animatedOnly: Boolean,
    animatedDurationActive: Boolean,
    hideLiked: Boolean,
    hideSaved: Boolean,
    hideWatched: Boolean,
    fullColor: Boolean,
    language: NhentaiLanguageFilter,
): Int = listOf(
    animatedOnly,
    animatedDurationActive,
    hideLiked,
    hideSaved,
    hideWatched,
    appliedQuery.sort != SortMode.NEWEST,
    appliedQuery.dateRange != null,
    appliedQuery.minScore != null,
    fullColor,
    language != NhentaiLanguageFilter.ANY,
).count { it }
