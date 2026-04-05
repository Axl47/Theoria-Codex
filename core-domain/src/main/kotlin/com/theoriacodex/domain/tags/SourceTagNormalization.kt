package com.theoriacodex.domain.tags

import com.theoriacodex.domain.model.SourceKey

private val SOURCE_TAG_WHITESPACE_REGEX = Regex("\\s+")

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
        SourceKey.GELBOORU, SourceKey.IWARA, SourceKey.RULE34XXX -> normalizeGelbooruToken(normalized)
        else -> normalized
    }
}

fun sourceTagKey(source: SourceKey, tag: String): String {
    return when (source) {
        SourceKey.GELBOORU, SourceKey.IWARA, SourceKey.RULE34XXX -> normalizeGelbooruToken(tag)
        SourceKey.PIXIV,
        SourceKey.NHENTAI,
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
        SourceKey.GELBOORU, SourceKey.IWARA, SourceKey.RULE34XXX ->
            normalizeGelbooruToken(normalizedLeft) == normalizeGelbooruToken(normalizedRight)
        SourceKey.PIXIV,
        SourceKey.NHENTAI,
        SourceKey.RULE34PAHEAL,
        SourceKey.RULE34VIDEO,
        SourceKey.RULE34GEN,
        -> normalizeMatchToken(normalizedLeft) == normalizeMatchToken(normalizedRight)
        else -> normalizedLeft.equals(normalizedRight, ignoreCase = true)
    }
}
