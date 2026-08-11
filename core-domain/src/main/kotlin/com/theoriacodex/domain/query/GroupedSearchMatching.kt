package com.theoriacodex.domain.query

import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.tags.sourceTagsMatch

fun Post.matchesIncludeTermGroups(query: Query): Boolean {
    return query.effectiveIncludeTermGroups.all { group ->
        group.terms.any(::matchesSearchTerm)
    }
}

private fun Post.matchesSearchTerm(term: SearchTerm): Boolean {
    val taxonomyMatches = taxonomy.any { candidate ->
        candidate.facet == term.facet &&
            candidate.sourceNamespace == term.sourceNamespace &&
            sourceTagsMatch(id.source, candidate.value, term.value)
    }
    if (taxonomyMatches) return true
    if (term.facet != SearchFacet.TAG || term.sourceNamespace != null) return false
    return (canonicalTags + rawTags).any { tag -> sourceTagsMatch(id.source, tag, term.value) }
}
