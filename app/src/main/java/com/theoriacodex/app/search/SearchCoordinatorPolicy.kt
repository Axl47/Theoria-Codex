package com.theoriacodex.app.search

import com.theoriacodex.domain.adapter.FacetedSearchScope
import com.theoriacodex.domain.adapter.FacetedTagSuggestion
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SearchTermGroup
import com.theoriacodex.domain.model.SortMode

internal data class SanitizedQuery(val query: Query, val removedSourceOwnedTerms: Boolean)

internal fun Query.sanitizedForMode(mode: QueryMode): SanitizedQuery {
    if (mode != QueryMode.Unified) return SanitizedQuery(copy(mode = mode), false)
    val includeGroups = effectiveIncludeTermGroups.filter(SearchTermGroup::isPortableGeneralTagGroup)
    val exclude = excludeTerms.filter(SearchTerm::isPortableGeneralTag)
    return SanitizedQuery(
        withIncludeTermGroups(includeGroups).copy(mode = mode, excludeTerms = exclude),
        includeGroups.size != effectiveIncludeTermGroups.size || exclude.size != excludeTerms.size,
    )
}

internal fun FacetedSearchScope.scopeOrder(): Int =
    if (sourceNamespace == null || facet == SearchFacet.TAG && sourceNamespace == "tag") 0 else 1

internal fun FacetedTagSuggestion.toLegacySuggestion() =
    TagSuggestion(text, sourceNamespace ?: facet.name.lowercase(), count)

internal fun FacetedTagSuggestion.isPortableTagSuggestion() =
    facet == SearchFacet.TAG && sourceNamespace in setOf(null, "tag")

internal fun FacetedTagSuggestion.toPortableLegacySuggestion() = TagSuggestion(text, "tag", count)

internal fun TagSuggestion.isPortableTagSuggestion() = type?.trim()?.lowercase() !in setOf(
    "artist", "character", "series", "parody", "group", "type", "category", "language", "female", "male",
)

internal fun defaultQuery(mode: QueryMode = QueryMode.Unified) = Query(
    mode = mode,
    includeTerms = emptyList(),
    excludeTerms = emptyList(),
    sort = SortMode.NEWEST,
    dateRange = null,
    minScore = null,
)

internal data class ResolveFailureRecord(
    val lastFailureAtMs: Long,
    val backoffUntilMs: Long,
    val reason: SourceFailureReason,
)

internal val SEARCH_SCOPE_ORDER = listOf(
    null, SearchFacet.TAG, SearchFacet.ARTIST, SearchFacet.CHARACTER, SearchFacet.SERIES,
    SearchFacet.GROUP, SearchFacet.TYPE, SearchFacet.LANGUAGE,
)
internal val SEARCH_SCOPE_COMPARATOR = compareBy<FacetedSearchScope> {
    SEARCH_SCOPE_ORDER.indexOf(it.facet)
}.thenBy(FacetedSearchScope::scopeOrder).thenBy { it.sourceNamespace.orEmpty() }
internal const val LAST_ACTIVE_QUERY_KEY = "last_active"
internal const val PIXIV_UNKNOWN_RETRY_MESSAGE =
    "Pixiv returned a temporary unknown error. Search was reset. Please retry."
internal const val FACETED_AUTOCOMPLETE_LIMIT = 20
internal const val FACETED_AUTOCOMPLETE_CACHE_LIMIT = 120
internal const val TAG_LOOKUP_LIMIT = 20_000
internal const val TAG_FETCH_LIMIT = 25
internal const val TRENDING_REFRESH_INTERVAL_MS = 12L * 60L * 60L * 1000L
internal const val TRENDING_FETCH_PER_SOURCE_LIMIT = 10
internal const val TRENDING_PER_SOURCE_CACHE_LIMIT = 40
internal const val SOURCE_TRENDING_LIMIT = 20
internal const val UNIFIED_TRENDING_LIMIT = 20
internal const val SEEN_TAGS_PER_SOURCE_INGEST_LIMIT = 240
internal const val MAX_RESOLVED_POST_OVERRIDES_PER_QUERY = 200
internal const val MAX_REMEMBERED_QUERY_OVERRIDES = 8
internal const val RATE_LIMIT_BACKOFF_FIRST_MS = 30_000L
internal const val RATE_LIMIT_BACKOFF_REPEAT_MS = 2L * 60L * 1000L
internal const val RATE_LIMIT_REPEAT_WINDOW_MS = 2L * 60L * 1000L
internal val WHITESPACE_REGEX = Regex("\\s+")
internal val PIXIV_TRAILING_PARENTHESIS_REGEX = Regex("\\s*\\([^)]*\\)\\s*$")
