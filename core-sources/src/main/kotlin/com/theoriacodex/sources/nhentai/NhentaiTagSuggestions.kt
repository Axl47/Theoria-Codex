package com.theoriacodex.sources.nhentai

import com.google.gson.JsonElement
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.sources.common.asIntOrNull
import com.theoriacodex.sources.common.asStringOrNull
import com.theoriacodex.sources.common.elementsOrEmpty
import com.theoriacodex.sources.common.optionalJsonArray

/** Collects the strongest count per case-insensitive tag while preserving first-wins ties. */
internal fun collectNhentaiTagSuggestions(
    galleries: List<JsonElement>,
    prefix: String?,
    limit: Int,
): List<TagSuggestion> {
    if (limit <= 0) return emptyList()
    val normalizedPrefix = prefix?.trim()?.lowercase().orEmpty()
    val byName = linkedMapOf<String, TagSuggestion>()

    galleries.flatMap { galleryElement ->
        galleryElement.takeIf(JsonElement::isJsonObject)?.asJsonObject
            ?.optionalJsonArray("tags").elementsOrEmpty()
    }.mapNotNull { element -> parseNhentaiSuggestion(element, normalizedPrefix) }
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

private fun parseNhentaiSuggestion(element: JsonElement, prefix: String): TagSuggestion? {
    val tag = element.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return null
    val name = tag.get("name").asStringOrNull()?.trim().orEmpty()
    if (name.isBlank() || (prefix.isNotBlank() && !name.lowercase().contains(prefix))) return null
    return TagSuggestion(
        text = name,
        type = tag.get("type").asStringOrNull()?.trim()?.takeIf(String::isNotBlank),
        count = tag.get("count").asIntOrNull(),
    )
}
