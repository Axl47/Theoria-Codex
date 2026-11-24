package com.theoriacodex.sources.common

import com.theoriacodex.domain.adapter.FacetedSearchScope
import com.theoriacodex.domain.model.SearchFacet

internal class FacetNamespaces(vararg entries: Pair<String, SearchFacet>) {
    private val facetByNamespace: Map<String, SearchFacet> = linkedMapOf(*entries)

    val namespaces: Set<String> = facetByNamespace.keys

    val searchScopes: Set<FacetedSearchScope> =
        linkedSetOf(FacetedSearchScope.All) + entries.map { (namespace, facet) -> FacetedSearchScope(facet, namespace) }

    fun facetOf(namespace: String): SearchFacet? = facetByNamespace[namespace]

    fun namespacesOf(facet: SearchFacet): Set<String> =
        facetByNamespace.filterValues { owner -> owner == facet }.keys

    fun defaultNamespace(facet: SearchFacet): String = namespacesOf(facet).first()
}
