package com.theoriacodex.app.search

import com.theoriacodex.domain.adapter.FacetedSearchScope
import com.theoriacodex.domain.adapter.FacetedTagSuggestion
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.tags.normalizeMatchToken
import com.theoriacodex.domain.tags.sourceTagKey

enum class TagSuggestionOrigin {
    SEED,
    AUTOCOMPLETE,
    TRENDING,
    SEEN,
    FEATURED,
    COUNT_LOOKUP,
}

interface TagSuggestionStore {
    fun get(source: SourceKey, limit: Int): List<TagSuggestion>
    fun getCount(source: SourceKey, tag: String): Int? = get(source, Int.MAX_VALUE)
        .firstOrNull { sourceTagKey(source, it.text) == sourceTagKey(source, tag) }?.count
    fun put(source: SourceKey, suggestions: List<TagSuggestion>)

    fun find(source: SourceKey, prefix: String, limit: Int): List<TagSuggestion> =
        get(source, Int.MAX_VALUE)
            .asSequence()
            .filter { suggestion -> tagSuggestionMatches(suggestion, prefix) }
            .take(limit.coerceAtLeast(0))
            .toList()

    fun getTrending(source: SourceKey, limit: Int): List<TagSuggestion> =
        get(source, Int.MAX_VALUE)
            .asSequence()
            .filter { suggestion -> suggestion.type.equals("trending", ignoreCase = true) }
            .take(limit.coerceAtLeast(0))
            .toList()

    fun put(
        source: SourceKey,
        suggestions: List<TagSuggestion>,
        origin: TagSuggestionOrigin,
    ) = put(source, suggestions)

    fun replaceTrending(source: SourceKey, suggestions: List<TagSuggestion>) =
        put(source, suggestions, TagSuggestionOrigin.TRENDING)

    fun trendingRefreshedAt(source: SourceKey): Long? = null

    fun markTrendingRefreshed(source: SourceKey, atEpochMs: Long) = Unit

    suspend fun awaitLoaded() = Unit
    suspend fun flush() = Unit
    suspend fun close() = Unit
    fun requestClose() = Unit

    fun getFaceted(
        source: SourceKey,
        limit: Int,
        scope: FacetedSearchScope,
    ): List<FacetedTagSuggestion> {
        if (!scope.isAll && (scope.facet != SearchFacet.TAG || scope.sourceNamespace != null)) {
            return emptyList()
        }
        return get(source, limit).map { suggestion ->
            FacetedTagSuggestion(
                text = suggestion.text,
                facet = SearchFacet.TAG,
                sourceNamespace = null,
                count = suggestion.count,
            )
        }
    }

    fun findFaceted(
        source: SourceKey,
        prefix: String,
        limit: Int,
        scope: FacetedSearchScope,
    ): List<FacetedTagSuggestion> = getFaceted(source, Int.MAX_VALUE, scope)
        .asSequence()
        .filter { suggestion ->
            val normalizedPrefix = normalizeMatchToken(prefix)
            normalizeMatchToken(suggestion.text).contains(normalizedPrefix)
        }
        .take(limit.coerceAtLeast(0))
        .toList()

    fun putFaceted(source: SourceKey, suggestions: List<FacetedTagSuggestion>) {
        put(
            source = source,
            suggestions = suggestions
                .filter { suggestion ->
                    suggestion.facet == SearchFacet.TAG && suggestion.sourceNamespace == null
                }
                .map { suggestion ->
                    TagSuggestion(
                        text = suggestion.text,
                        type = null,
                        count = suggestion.count,
                    )
                },
        )
    }

    fun putFaceted(
        source: SourceKey,
        suggestions: List<FacetedTagSuggestion>,
        origin: TagSuggestionOrigin,
    ) = putFaceted(source, suggestions)
}

object NoOpTagSuggestionStore : TagSuggestionStore {
    override fun get(source: SourceKey, limit: Int): List<TagSuggestion> = emptyList()
    override fun put(source: SourceKey, suggestions: List<TagSuggestion>) = Unit

    override fun getFaceted(
        source: SourceKey,
        limit: Int,
        scope: FacetedSearchScope,
    ): List<FacetedTagSuggestion> = emptyList()

    override fun putFaceted(source: SourceKey, suggestions: List<FacetedTagSuggestion>) = Unit
}
