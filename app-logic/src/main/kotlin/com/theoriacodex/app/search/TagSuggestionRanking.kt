package com.theoriacodex.app.search

import com.theoriacodex.domain.adapter.FacetedTagSuggestion
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.tags.normalizeMatchToken

/** Pure ranking policy shared by cached and provider-backed Search suggestions. */
fun rankTagSuggestions(
    suggestions: List<TagSuggestion>,
    prefix: String,
    limit: Int,
): List<TagSuggestion> {
    if (limit <= 0) return emptyList()
    val indexed = suggestions.mapIndexedNotNull { index, suggestion ->
        suggestion.matchQuality(prefix)?.let { quality -> RankedSuggestion(suggestion, quality, index) }
    }
    return indexed
        .distinctBy { ranked -> normalizeMatchToken(ranked.suggestion.text) }
        .sortedWith(
            compareBy<RankedSuggestion>(RankedSuggestion::quality)
                .thenByDescending { ranked -> ranked.suggestion.count ?: Int.MIN_VALUE }
                .thenBy(RankedSuggestion::originalIndex),
        )
        .take(limit)
        .map(RankedSuggestion::suggestion)
}

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
    MatchQuality.entries.forEach { quality ->
        val queues = suggestionsBySource.map { (_, suggestions) ->
            rankTagSuggestions(suggestions, prefix, suggestions.size)
                .filter { suggestion -> suggestion.matchQuality(prefix) == quality }
                .toMutableList()
        }
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

fun rankFacetedTagSuggestions(
    suggestions: List<FacetedTagSuggestion>,
    prefix: String,
    limit: Int,
): List<FacetedTagSuggestion> {
    if (limit <= 0) return emptyList()
    val normalized = normalizeMatchToken(prefix)
    return suggestions.mapIndexedNotNull { index, suggestion ->
        normalizeMatchToken(suggestion.text).matchQuality(normalized)?.let { quality ->
            RankedFacetedSuggestion(suggestion, quality, index)
        }
    }
        .distinctBy { ranked ->
            Triple(
                ranked.suggestion.facet,
                ranked.suggestion.sourceNamespace,
                normalizeMatchToken(ranked.suggestion.text),
            )
        }
        .sortedWith(
            compareBy<RankedFacetedSuggestion>(RankedFacetedSuggestion::quality)
                .thenByDescending { ranked -> ranked.suggestion.count ?: Int.MIN_VALUE }
                .thenBy(RankedFacetedSuggestion::originalIndex),
        )
        .take(limit)
        .map(RankedFacetedSuggestion::suggestion)
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

private data class RankedSuggestion(
    val suggestion: TagSuggestion,
    val quality: MatchQuality,
    val originalIndex: Int,
)

private data class RankedFacetedSuggestion(
    val suggestion: FacetedTagSuggestion,
    val quality: MatchQuality,
    val originalIndex: Int,
)
