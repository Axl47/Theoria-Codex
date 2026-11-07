package com.theoriacodex.app.search

import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.query.SearchTermLimit

/**
 * A source that cannot take the whole query. Unified sends it only the first [budget] terms; a
 * search of that source alone past its budget is refused ([blocksSearch]). [reducedBySort] means
 * the chosen sort spends one of the source's terms.
 */
data class SearchTermLimitNotice(
    val source: SourceKey,
    val budget: Int,
    val blocksSearch: Boolean,
    val reducedBySort: Boolean,
)

/** Notices for each of [sources] whose [limits] the query exceeds, in [sources] order. */
fun searchTermLimitNotices(
    query: Query,
    sources: Iterable<SourceKey>,
    limits: Map<SourceKey, SearchTermLimit>,
): List<SearchTermLimitNotice> {
    // Unified only sends portable terms, so source-owned terms never count against a limit there.
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
