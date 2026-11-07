package com.theoriacodex.domain.tags

import com.theoriacodex.domain.model.SourceKey

private val SOURCE_TAG_WHITESPACE_REGEX = Regex("\\s+")

/** Booru-style sources whose tags join words with underscores. */
private val UNDERSCORE_TAG_SOURCES = setOf(
    SourceKey.GELBOORU, SourceKey.AIBOORU, SourceKey.DANBOORU, SourceKey.IWARA, SourceKey.RULE34XXX,
)

/** The provider spelling of typed input, used as an autocomplete prefix. */
fun sourceAutocompletePrefix(source: SourceKey, input: String): String =
    if (source in UNDERSCORE_TAG_SOURCES) normalizeGelbooruToken(input) else input

fun normalizeMatchToken(value: String): String {
    return value
        .trim()
        .lowercase()
        .replace('_', ' ')
        .replace(SOURCE_TAG_WHITESPACE_REGEX, " ")
}

fun normalizeGelbooruToken(value: String): String {
    return value
        .trim()
        .lowercase()
        .replace(SOURCE_TAG_WHITESPACE_REGEX, "_")
}

fun normalizeFavoriteTagForStorage(source: SourceKey, tag: String): String {
    val normalized = tag.trim()
    if (normalized.isBlank()) return ""
    return when (source) {
        in UNDERSCORE_TAG_SOURCES -> normalizeGelbooruToken(normalized)
        else -> normalized
    }
}

fun sourceTagKey(source: SourceKey, tag: String): String {
    return when (source) {
        in UNDERSCORE_TAG_SOURCES -> normalizeGelbooruToken(tag)
        SourceKey.PIXIV,
        SourceKey.NHENTAI,
        SourceKey.HITOMI,
        SourceKey.RULE34PAHEAL,
        SourceKey.RULE34VIDEO,
        SourceKey.RULE34GEN,
        -> normalizeMatchToken(tag)
        else -> tag.trim().lowercase()
    }
}

fun sourceTagsMatch(source: SourceKey, left: String, right: String): Boolean {
    val normalizedLeft = left.trim()
    val normalizedRight = right.trim()
    if (normalizedLeft.isBlank() || normalizedRight.isBlank()) return false
    return when (source) {
        in UNDERSCORE_TAG_SOURCES ->
            normalizeGelbooruToken(normalizedLeft) == normalizeGelbooruToken(normalizedRight)
        SourceKey.PIXIV,
        SourceKey.NHENTAI,
        SourceKey.HITOMI,
        SourceKey.RULE34PAHEAL,
        SourceKey.RULE34VIDEO,
        SourceKey.RULE34GEN,
        -> normalizeMatchToken(normalizedLeft) == normalizeMatchToken(normalizedRight)
        else -> normalizedLeft.equals(normalizedRight, ignoreCase = true)
    }
}
