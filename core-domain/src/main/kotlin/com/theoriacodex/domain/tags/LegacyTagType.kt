package com.theoriacodex.domain.tags

import java.util.Locale

/** A legacy `TagSuggestion.type` as compared everywhere: trimmed and lowercased, or null when absent. */
fun legacyTagType(type: String?): String? = type?.trim()?.lowercase(Locale.ROOT)

private val LEGACY_FACET_TYPES = setOf(
    "artist", "character", "series", "parody", "group", "type", "category", "language",
)

/** True when a legacy suggestion type names a facet such as artist or language rather than a general tag. */
fun isLegacyFacetType(type: String?): Boolean = legacyTagType(type) in LEGACY_FACET_TYPES
