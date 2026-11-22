package com.theoriacodex.domain.query

import com.theoriacodex.domain.adapter.SourceCapabilities
import com.theoriacodex.domain.model.MediaFilter
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SearchTermGroup

/**
 * The request a source with [capabilities] receives for this query: an animated search gains the source's animated
 * term, or its no-cost fallback when the term limit is full. Only the outgoing request carries it; the draft, history
 * and statistics keep the query as the user wrote it.
 */
fun Query.withMediaTerms(capabilities: SourceCapabilities): Query {
    if (media != MediaFilter.ANIMATED) return this
    val animated = capabilities.animatedTerm ?: return this
    // Only a group that requires the term asks for it; one alternative of an OR group leaves the others mixed.
    val alreadyAsked = includeTermGroups.any { group ->
        group.terms.all { term -> term.tagType == animated.term.tagType && term.value.equals(animated.term.value, ignoreCase = true) }
    }
    if (alreadyAsked) return this
    val fits = capabilities.searchTermLimit?.let { limit -> requestTermCount < limit.budgetFor(sort) } ?: true
    val term = if (fits) animated.term else animated.overLimit ?: return this
    return withIncludeTermGroups(effectiveIncludeTermGroups + SearchTermGroup.single(term))
}
