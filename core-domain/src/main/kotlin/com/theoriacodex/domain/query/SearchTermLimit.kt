package com.theoriacodex.domain.query

import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SortMode

data class SearchTermLimit(
    val maxTerms: Int,
    val rankedSortUsesTerm: Boolean = false,
) {
    init {
        require(maxTerms >= 1) { "A search term limit must allow at least one term" }
    }

    fun budgetFor(sort: SortMode): Int =
        if (rankedSortUsesTerm && sort != SortMode.NEWEST) (maxTerms - 1).coerceAtLeast(1) else maxTerms

    fun allows(query: Query): Boolean = query.requestTermCount <= budgetFor(query.sort)
}

val Query.requestTermCount: Int
    get() = effectiveIncludeTermGroups.size + excludeTerms.size

fun Query.limitedToTerms(budget: Int): Query {
    val groups = effectiveIncludeTermGroups.take(budget)
    val excluded = excludeTerms.take((budget - groups.size).coerceAtLeast(0))
    return withIncludeTermGroups(groups).copy(excludeTerms = excluded)
}

fun Query.withinTermLimit(limit: SearchTermLimit?): Query =
    if (limit == null || limit.allows(this)) this else limitedToTerms(limit.budgetFor(sort))
