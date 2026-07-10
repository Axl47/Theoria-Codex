package com.theoriacodex.app.search

import com.theoriacodex.domain.adapter.FacetedSearchScope
import com.theoriacodex.domain.adapter.FacetedTagSuggestion
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm

internal val primarySearchFacets: List<SearchFacet> = listOf(
    SearchFacet.TAG,
    SearchFacet.ARTIST,
    SearchFacet.CHARACTER,
    SearchFacet.SERIES,
)

internal val secondarySearchFacets: List<SearchFacet> = listOf(
    SearchFacet.GROUP,
    SearchFacet.TYPE,
    SearchFacet.LANGUAGE,
)

internal fun searchScopeLabel(scope: FacetedSearchScope): String {
    val facet = scope.facet ?: return "All"
    return when (facet) {
        SearchFacet.TAG -> when (scope.sourceNamespace?.lowercase()) {
            "female" -> "Female tags"
            "male" -> "Male tags"
            else -> "Tags"
        }
        SearchFacet.ARTIST -> "Artists"
        SearchFacet.CHARACTER -> "Characters"
        SearchFacet.SERIES -> "Series"
        SearchFacet.GROUP -> "Groups"
        SearchFacet.TYPE -> "Types"
        SearchFacet.LANGUAGE -> "Languages"
    }
}

internal fun searchFacetLabel(facet: SearchFacet, sourceNamespace: String?): String {
    return when (facet) {
        SearchFacet.TAG -> when (sourceNamespace?.lowercase()) {
            "female" -> "Female tag"
            "male" -> "Male tag"
            else -> "Tag"
        }
        SearchFacet.ARTIST -> "Artist"
        SearchFacet.CHARACTER -> "Character"
        SearchFacet.SERIES -> "Series"
        SearchFacet.GROUP -> "Group"
        SearchFacet.TYPE -> "Type"
        SearchFacet.LANGUAGE -> "Language"
    }
}

internal fun searchTermChipLabel(term: SearchTerm, excluded: Boolean): String {
    val polarity = if (excluded) "− " else ""
    if (term.isPortableGeneralTag) return "$polarity${term.value}"
    return "$polarity${searchFacetLabel(term.facet, term.sourceNamespace)} · ${term.value}"
}

internal fun facetedSuggestionMetaLabel(suggestion: FacetedTagSuggestion): String {
    val facet = searchFacetLabel(suggestion.facet, suggestion.sourceNamespace)
    val count = suggestion.count?.toString()
    return listOfNotNull(facet, count).joinToString(" • ")
}
