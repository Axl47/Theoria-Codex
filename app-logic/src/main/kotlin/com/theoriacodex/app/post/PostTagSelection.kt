package com.theoriacodex.app.post

import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.tags.sourceTagKey
import java.util.Locale

/** The real draft is the only selection authority; removal returns its exact stored term. */
class PostTagSelection(private val source: SourceKey, includes: List<SearchTerm>, excludes: List<SearchTerm>) {
    private val included = includes.associateBy(::key)
    private val excluded = excludes.associateBy(::key)

    fun included(term: SearchTerm): SearchTerm? = included[key(term)]
    fun excluded(term: SearchTerm): SearchTerm? = excluded[key(term)]

    private fun key(term: SearchTerm): TermKey = TermKey(term.facet,
        term.sourceNamespace?.trim()?.lowercase(Locale.ROOT)?.takeUnless { term.facet == SearchFacet.TAG && it == "tag" },
        sourceTagKey(source, term.value))

    private data class TermKey(val facet: SearchFacet, val namespace: String?, val value: String)
}
