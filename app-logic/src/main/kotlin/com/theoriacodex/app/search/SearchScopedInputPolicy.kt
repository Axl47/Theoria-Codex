package com.theoriacodex.app.search

import com.theoriacodex.domain.adapter.FacetedSearchScope
import com.theoriacodex.domain.model.SearchFacet

data class SearchScopePrefix(
    val facet: SearchFacet,
    val sourceNamespace: String? = null,
    val requiresExactNamespace: Boolean = false,
)

data class ParsedScopedInput(
    val value: String,
    val isExclude: Boolean,
    val explicitScope: SearchScopePrefix?,
)

fun parseScopedInput(input: String): ParsedScopedInput {
    val trimmed = input.trim()
    val excluded = trimmed.startsWith('-')
    val unsigned = trimmed.removePrefix("-").trim()
    val separator = unsigned.indexOf(':')
    if (separator <= 0) return ParsedScopedInput(unsigned, excluded, null)
    val scope = SEARCH_SCOPE_PREFIXES[unsigned.substring(0, separator).trim().lowercase()]
        ?: return ParsedScopedInput(unsigned, excluded, null)
    return ParsedScopedInput(unsigned.substring(separator + 1).trim(), excluded, scope)
}

fun resolveSupportedScope(
    prefix: SearchScopePrefix,
    scopes: List<FacetedSearchScope>,
): FacetedSearchScope? {
    scopes.firstOrNull { it.facet == prefix.facet && it.sourceNamespace == prefix.sourceNamespace }
        ?.let { return it }
    if (prefix.requiresExactNamespace) return null
    return scopes.firstOrNull { it.facet == prefix.facet && it.sourceNamespace == null }
        ?: scopes.firstOrNull { it.facet == prefix.facet }
}

const val UNIFIED_SCOPED_INPUT_BLOCKED_MESSAGE =
    "Artists, series, characters, groups, types, and languages require a specific source."
const val UNIFIED_SOURCE_TERMS_REMOVED_MESSAGE =
    "Source-specific search terms were removed when switching to Unified."
const val UNSUPPORTED_SEARCH_SCOPE_MESSAGE = "That search scope is not supported by this source."

private val SEARCH_SCOPE_PREFIXES = mapOf(
    "tag" to SearchScopePrefix(SearchFacet.TAG, "tag"),
    "female" to SearchScopePrefix(SearchFacet.TAG, "female", true),
    "male" to SearchScopePrefix(SearchFacet.TAG, "male", true),
    "artist" to SearchScopePrefix(SearchFacet.ARTIST),
    "character" to SearchScopePrefix(SearchFacet.CHARACTER),
    "series" to SearchScopePrefix(SearchFacet.SERIES),
    "parody" to SearchScopePrefix(SearchFacet.SERIES, "parody", true),
    "group" to SearchScopePrefix(SearchFacet.GROUP),
    "type" to SearchScopePrefix(SearchFacet.TYPE),
    "category" to SearchScopePrefix(SearchFacet.TYPE, "category", true),
    "language" to SearchScopePrefix(SearchFacet.LANGUAGE),
)
