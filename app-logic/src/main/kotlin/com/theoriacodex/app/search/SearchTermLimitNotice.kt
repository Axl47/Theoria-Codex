package com.theoriacodex.app.search

import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.query.SearchTermLimit

data class SearchTermLimitNotice(
    val source: SourceKey,
    val budget: Int,
    val blocksSearch: Boolean,
    val reducedBySort: Boolean,
)

fun searchTermLimitNotices(
    query: Query,
    sources: Iterable<SourceKey>,
    limits: Map<SourceKey, SearchTermLimit>,
): List<SearchTermLimitNotice> {
    val sent = query.portableTermsForUnified()
    return sources.mapNotNull { source ->
        val limit = limits[source]?.takeUnless { it.allows(sent) } ?: return@mapNotNull null
        val budget = limit.budgetFor(query.sort)
        SearchTermLimitNotice(
            source = source,
            budget = budget,
            blocksSearch = query.mode is QueryMode.Source,
            reducedBySort = budget < limit.maxTerms,
        )
    }
}
