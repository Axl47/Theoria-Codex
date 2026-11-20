package com.theoriacodex.sources.nhentai

import com.google.gson.JsonObject
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.sources.common.asIntOrNull
import com.theoriacodex.sources.common.asStringOrNull
import com.theoriacodex.sources.common.objects

/** Collects the strongest count per case-insensitive tag while preserving first-wins ties. */
internal fun collectNhentaiTagSuggestions(
    galleries: List<JsonObject>,
    prefix: String?,
    limit: Int,
): List<TagSuggestion> {
    if (limit <= 0) return emptyList()
    val normalizedPrefix = prefix?.trim()?.lowercase().orEmpty()
    val byName = linkedMapOf<String, TagSuggestion>()

    galleries.flatMap { gallery -> gallery.objects("tags") }
        .mapNotNull { tag -> parseNhentaiSuggestion(tag, normalizedPrefix) }
        .forEach { candidate ->
            val key = candidate.text.lowercase()
            val existing = byName[key]
            if (existing == null || (candidate.count ?: Int.MIN_VALUE) > (existing.count ?: Int.MIN_VALUE)) {
                byName[key] = candidate
            }
        }

    return byName.values
        .sortedWith(
            compareByDescending<TagSuggestion> { suggestion -> suggestion.count ?: Int.MIN_VALUE }
                .thenBy { suggestion -> suggestion.text.lowercase() }
        )
        .take(limit)
}

private fun parseNhentaiSuggestion(tag: JsonObject, prefix: String): TagSuggestion? {
    val name = tag.get("name").asStringOrNull()?.trim().orEmpty()
    if (name.isBlank() || (prefix.isNotBlank() && !name.lowercase().contains(prefix))) return null
    return TagSuggestion(
        text = name,
        type = tag.get("type").asStringOrNull()?.trim()?.takeIf(String::isNotBlank),
        count = tag.get("count").asIntOrNull(),
    )
}
