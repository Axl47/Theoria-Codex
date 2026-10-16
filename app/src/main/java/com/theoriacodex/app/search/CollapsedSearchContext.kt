package com.theoriacodex.app.search

import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SortMode

internal fun collapsedSearchContextSummary(
    appliedQuery: Query,
    activeFilterCount: Int,
): String? {
    val query = appliedQuery.effectiveIncludeTermGroups.takeIf(List<*>::isNotEmpty)?.joinToString(" & ") { group ->
        if (group.terms.size == 1) group.terms.single().value
        else group.terms.joinToString(prefix = "(", postfix = ")", separator = " || ") { it.value }
    }
        ?: appliedQuery.excludeTerms.firstOrNull()?.value?.let { value -> "-$value" }
    return buildList {
        query?.takeIf(String::isNotBlank)?.let(::add)
        if (activeFilterCount > 0) {
            add("$activeFilterCount ${if (activeFilterCount == 1) "filter" else "filters"}")
        }
    }.joinToString(" · ").takeIf(String::isNotBlank)
}

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
