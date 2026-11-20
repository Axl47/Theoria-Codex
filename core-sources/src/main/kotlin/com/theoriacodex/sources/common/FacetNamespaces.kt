package com.theoriacodex.sources.common

import com.theoriacodex.domain.adapter.FacetedSearchScope
import com.theoriacodex.domain.model.SearchFacet

/**
 * A faceted source's one table of provider namespaces and the app facet each belongs to, listed in the order
 * the source offers them as search scopes. A facet's first namespace is its default.
 */
internal class FacetNamespaces(vararg entries: Pair<String, SearchFacet>) {
    private val facetByNamespace: Map<String, SearchFacet> = linkedMapOf(*entries)

    val namespaces: Set<String> = facetByNamespace.keys

    /** [FacetedSearchScope.All] followed by one scope per namespace. */
    val searchScopes: Set<FacetedSearchScope> =
        linkedSetOf(FacetedSearchScope.All) + entries.map { (namespace, facet) -> FacetedSearchScope(facet, namespace) }

    fun facetOf(namespace: String): SearchFacet? = facetByNamespace[namespace]

    fun namespacesOf(facet: SearchFacet): Set<String> =
        facetByNamespace.filterValues { owner -> owner == facet }.keys

    fun defaultNamespace(facet: SearchFacet): String = namespacesOf(facet).first()
}
