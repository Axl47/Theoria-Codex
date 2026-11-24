package com.theoriacodex.domain.query

import com.theoriacodex.domain.adapter.SourceCapabilities
import com.theoriacodex.domain.model.MediaFilter
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SearchTermGroup

fun Query.withMediaTerms(capabilities: SourceCapabilities): Query {
    if (media != MediaFilter.ANIMATED) return this
    val animated = capabilities.animatedTerm ?: return this
    val alreadyAsked = includeTermGroups.any { group ->
        group.terms.all { term -> term.tagType == animated.term.tagType && term.value.equals(animated.term.value, ignoreCase = true) }
    }
    if (alreadyAsked) return this
    val fits = capabilities.searchTermLimit?.let { limit -> requestTermCount < limit.budgetFor(sort) } ?: true
    val term = if (fits) animated.term else animated.overLimit ?: return this
    return withIncludeTermGroups(effectiveIncludeTermGroups + SearchTermGroup.single(term))
}
