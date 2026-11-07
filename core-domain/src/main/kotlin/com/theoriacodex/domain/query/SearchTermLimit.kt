package com.theoriacodex.domain.query

import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SortMode

/**
 * A provider's cap on the terms one search request may carry, such as Danbooru's two-tag guest
 * limit. When [rankedSortUsesTerm] is set, a ranked sort spends one of those terms on the
 * provider's own order metatag, leaving one fewer for the user's tags.
 */
data class SearchTermLimit(
    val maxTerms: Int,
    val rankedSortUsesTerm: Boolean = false,
) {
    init {
        require(maxTerms >= 1) { "A search term limit must allow at least one term" }
    }

    /** How many of the user's terms one request can carry under [sort]. */
    fun budgetFor(sort: SortMode): Int =
        if (rankedSortUsesTerm && sort != SortMode.NEWEST) (maxTerms - 1).coerceAtLeast(1) else maxTerms

    fun allows(query: Query): Boolean = query.requestTermCount <= budgetFor(query.sort)
}

/**
 * Terms one provider request carries. Sources without grouped search send one alternative of each
 * "or" group per request, so a group costs one term.
 */
val Query.requestTermCount: Int
    get() = effectiveIncludeTermGroups.size + excludeTerms.size

/** Keeps the first [budget] terms, required groups before exclusions, in the order they were added. */
fun Query.limitedToTerms(budget: Int): Query {
    val groups = effectiveIncludeTermGroups.take(budget)
    val excluded = excludeTerms.take((budget - groups.size).coerceAtLeast(0))
    return withIncludeTermGroups(groups).copy(excludeTerms = excluded)
}

/** The part of this query a source with [limit] can send; unlimited sources get it unchanged. */
fun Query.withinTermLimit(limit: SearchTermLimit?): Query =
    if (limit == null || limit.allows(this)) this else limitedToTerms(limit.budgetFor(sort))
