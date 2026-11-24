package com.theoriacodex.domain.tags

import java.util.Locale

fun legacyTagType(type: String?): String? = type?.trim()?.lowercase(Locale.ROOT)

private val LEGACY_FACET_TYPES = setOf(
    "artist", "character", "series", "parody", "group", "type", "category", "language",
)

fun isLegacyFacetType(type: String?): Boolean = legacyTagType(type) in LEGACY_FACET_TYPES
