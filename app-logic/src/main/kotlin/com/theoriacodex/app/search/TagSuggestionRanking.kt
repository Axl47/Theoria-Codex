package com.theoriacodex.app.search

import com.theoriacodex.data.repository.SearchedTagKey
import com.theoriacodex.domain.adapter.FacetedTagSuggestion
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.tags.normalizeMatchToken

/** Pure ranking policy shared by cached and provider-backed Search suggestions. */
fun rankTagSuggestions(
    suggestions: List<TagSuggestion>,
    prefix: String,
    limit: Int,
): List<TagSuggestion> = rankSuggestions(
    suggestions = suggestions,
    limit = limit,
    qualityOf = { suggestion -> if (prefix.isBlank()) MatchQuality.PREFIX else suggestion.matchQuality(prefix) },
    identityOf = { suggestion -> normalizeMatchToken(suggestion.text) },
    countOf = TagSuggestion::count,
)

/**
 * Interleaves one relevance band at a time so providers with incomparable count scales remain
 * discoverable. Each provider still retains its own count and authoritative-order ranking.
 */
fun rankUnifiedTagSuggestions(
    suggestionsBySource: List<Pair<SourceKey, List<TagSuggestion>>>,
    prefix: String,
    limit: Int,
): List<TagSuggestion> {
    if (limit <= 0) return emptyList()
    val accepted = mutableListOf<TagSuggestion>()
    val seen = mutableSetOf<String>()
    val rankedBySource = suggestionsBySource.map { (_, suggestions) ->
        rankTagSuggestions(suggestions, prefix, suggestions.size).groupBy { it.matchQuality(prefix) }
    }
    MatchQuality.entries.forEach { quality ->
        val queues = rankedBySource.map { bands -> ArrayDeque(bands[quality].orEmpty()) }
        while (accepted.size < limit && queues.any(List<TagSuggestion>::isNotEmpty)) {
            queues.forEach { queue ->
                val suggestion = queue.removeFirstOrNull() ?: return@forEach
                if (seen.add(normalizeMatchToken(suggestion.text))) accepted += suggestion
            }
        }
    }
    return accepted.take(limit)
}

/** Fairly combines already provider-ranked lists, preserving each source's order. */
fun interleaveTagSuggestions(
    suggestionsBySource: List<Pair<SourceKey, List<TagSuggestion>>>,
    limit: Int,
): List<TagSuggestion> {
    if (limit <= 0) return emptyList()
    val queues = suggestionsBySource.map { (_, suggestions) -> suggestions.toMutableList() }
    val accepted = mutableListOf<TagSuggestion>()
    val seen = mutableSetOf<String>()
    while (accepted.size < limit && queues.any(List<TagSuggestion>::isNotEmpty)) {
        queues.forEach { queue ->
            val suggestion = queue.removeFirstOrNull() ?: return@forEach
            if (seen.add(normalizeMatchToken(suggestion.text))) accepted += suggestion
        }
    }
    return accepted.take(limit)
}

/** Same order as [rankTagSuggestions], but matches on the value only and keeps one row per facet and namespace. */
fun rankFacetedTagSuggestions(
    suggestions: List<FacetedTagSuggestion>,
    prefix: String,
    limit: Int,
): List<FacetedTagSuggestion> {
    val normalizedPrefix = normalizeMatchToken(prefix)
    return rankSuggestions(
        suggestions = suggestions,
        limit = limit,
        qualityOf = { suggestion -> normalizeMatchToken(suggestion.text).matchQuality(normalizedPrefix) },
        identityOf = { suggestion ->
            Triple(suggestion.facet, suggestion.sourceNamespace, normalizeMatchToken(suggestion.text))
        },
        countOf = FacetedTagSuggestion::count,
    )
}

/**
 * Drops items with no match, keeps the first of each [identityOf], then orders by match quality, highest
 * count, and original position. Shared by every suggestion element type.
 */
private fun <T, K> rankSuggestions(
    suggestions: List<T>,
    limit: Int,
    qualityOf: (T) -> MatchQuality?,
    identityOf: (T) -> K,
    countOf: (T) -> Int?,
): List<T> {
    if (limit <= 0) return emptyList()
    return suggestions
        .mapIndexedNotNull { index, suggestion ->
            qualityOf(suggestion)?.let { quality -> RankedSuggestion(suggestion, quality, index) }
        }
        .distinctBy { ranked -> identityOf(ranked.suggestion) }
        .sortedWith(
            compareBy<RankedSuggestion<T>> { ranked -> ranked.quality }
                .thenByDescending { ranked -> countOf(ranked.suggestion) ?: Int.MIN_VALUE }
                .thenBy { ranked -> ranked.originalIndex },
        )
        .take(limit)
        .map { ranked -> ranked.suggestion }
}

/**
 * Terms the user searched before on any of [sources] that match [prefix] and that [accepts] allows:
 * closest match first, then most-searched. Spellings of one tag type that normalize alike are one tag,
 * counted by the source that searched it most; the same value as another type stays a separate row.
 */
fun rankSearchedTags(
    searchCounts: Map<SearchedTagKey, Long>,
    sources: Set<SourceKey>,
    prefix: String,
    limit: Int,
    accepts: (SearchTerm) -> Boolean,
): List<SearchTerm> {
    val normalizedPrefix = normalizeMatchToken(prefix)
    if (limit <= 0 || normalizedPrefix.isBlank()) return emptyList()
    return searchCounts.entries
        .filter { (key, _) -> key.source in sources && accepts(key.term) }
        .mapNotNull { (key, count) ->
            val token = normalizeMatchToken(key.term.value)
            token.matchQuality(normalizedPrefix)?.let { quality -> SearchedTag(key.term, token, quality, count) }
        }
        .groupBy { searched -> searched.term.tagType to searched.token }
        .map { (_, spellings) -> spellings.maxBy(SearchedTag::count) }
        .sortedWith(compareBy(SearchedTag::quality).thenByDescending(SearchedTag::count).thenBy(SearchedTag::token))
        .take(limit)
        .map(SearchedTag::term)
}

fun tagSuggestionMatches(suggestion: TagSuggestion, prefix: String): Boolean =
    suggestion.matchQuality(prefix) != null

private fun TagSuggestion.matchQuality(prefix: String): MatchQuality? {
    val normalizedPrefix = normalizeMatchToken(prefix)
    if (normalizedPrefix.isBlank()) return null
    return sequenceOf(text, alternateText)
        .filterNotNull()
        .map(::normalizeMatchToken)
        .mapNotNull { candidate -> candidate.matchQuality(normalizedPrefix) }
        .minOrNull()
}

private fun String.matchQuality(normalizedPrefix: String): MatchQuality? = when {
    this == normalizedPrefix -> MatchQuality.EXACT
    startsWith(normalizedPrefix) -> MatchQuality.PREFIX
    contains(normalizedPrefix) -> MatchQuality.CONTAINS
    else -> null
}

private enum class MatchQuality {
    EXACT,
    PREFIX,
    CONTAINS,
}

private data class RankedSuggestion<T>(
    val suggestion: T,
    val quality: MatchQuality,
    val originalIndex: Int,
)

private data class SearchedTag(
    val term: SearchTerm,
    val token: String,
    val quality: MatchQuality,
    val count: Long,
)
