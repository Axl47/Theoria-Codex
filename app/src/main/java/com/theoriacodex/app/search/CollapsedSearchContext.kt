package com.theoriacodex.app.search

import com.theoriacodex.app.search.state.SearchSourceScope
import com.theoriacodex.app.source.displayName
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SortMode

internal fun collapsedSearchContextSummary(
    appliedSourceScope: SearchSourceScope,
    appliedQuery: Query,
    activeFilterCount: Int,
): String {
    val source = when (appliedSourceScope) {
        SearchSourceScope.GlobalUnified -> "Unified"
        is SearchSourceScope.Single -> appliedSourceScope.source.displayName()
        is SearchSourceScope.Temporary -> {
            val first = appliedSourceScope.sources.first().displayName()
            "$first +${appliedSourceScope.sources.size - 1}"
        }
    }
    val query = appliedQuery.effectiveIncludeTermGroups.takeIf(List<*>::isNotEmpty)?.joinToString(" AND ") { group ->
        if (group.terms.size == 1) group.terms.single().value
        else group.terms.joinToString(prefix = "(", postfix = ")", separator = " OR ") { it.value }
    }
        ?: appliedQuery.excludeTerms.firstOrNull()?.value?.let { value -> "-$value" }
    return buildList {
        add(source)
        query?.takeIf(String::isNotBlank)?.let(::add)
        if (activeFilterCount > 0) {
            add("$activeFilterCount ${if (activeFilterCount == 1) "filter" else "filters"}")
        }
    }.joinToString(" · ")
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
