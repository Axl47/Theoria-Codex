package com.theoriacodex.domain.tags

import com.theoriacodex.domain.model.SourceKey

private val SOURCE_TAG_WHITESPACE_REGEX = Regex("\\s+")
private val HTML_ENTITY_REGEX = Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{2,6});")
private val NAMED_HTML_ENTITIES = mapOf(
    "amp" to '&'.code, "lt" to '<'.code, "gt" to '>'.code, "quot" to '"'.code, "apos" to '\''.code,
)

enum class TagDialect {
    UNDERSCORE,

    SPACED,
}

private val UNDERSCORE_BUILT_INS = setOf(
    SourceKey.GELBOORU, SourceKey.AIBOORU, SourceKey.DANBOORU, SourceKey.IWARA, SourceKey.RULE34XXX,
)

val SourceKey.tagDialect: TagDialect
    get() = if (isCustom || this in UNDERSCORE_BUILT_INS) TagDialect.UNDERSCORE else TagDialect.SPACED

fun sourceAutocompletePrefix(source: SourceKey, input: String): String = when (source.tagDialect) {
    TagDialect.UNDERSCORE -> normalizeGelbooruToken(input)
    TagDialect.SPACED -> input
}

fun normalizeMatchToken(value: String): String {
    return value
        .trim()
        .lowercase()
        .replace('_', ' ')
        .replace(SOURCE_TAG_WHITESPACE_REGEX, " ")
}

fun normalizeGelbooruToken(value: String): String {
    return decodeHtmlEntities(value)
        .trim()
        .lowercase()
        .replace(SOURCE_TAG_WHITESPACE_REGEX, "_")
}

fun decodeHtmlEntities(value: String): String {
    if ('&' !in value) return value
    return HTML_ENTITY_REGEX.replace(value) { match ->
        val body = match.groupValues[1]
        val codePoint = when {
            body.startsWith("#x", ignoreCase = true) -> body.substring(2).toIntOrNull(radix = 16)
            body.startsWith("#") -> body.substring(1).toIntOrNull()
            else -> NAMED_HTML_ENTITIES[body.lowercase()]
        }
        codePoint?.takeIf(Character::isValidCodePoint)?.let { String(Character.toChars(it)) } ?: match.value
    }
}

fun normalizeFavoriteTagForStorage(source: SourceKey, tag: String): String {
    val normalized = tag.trim()
    if (normalized.isBlank()) return ""
    return when (source.tagDialect) {
        TagDialect.UNDERSCORE -> normalizeGelbooruToken(normalized)
        TagDialect.SPACED -> normalized
    }
}

fun sourceTagKey(source: SourceKey, tag: String): String {
    return when (source.tagDialect) {
        TagDialect.UNDERSCORE -> normalizeGelbooruToken(tag)
        TagDialect.SPACED -> normalizeMatchToken(tag)
    }
}

fun sourceTagsMatch(source: SourceKey, left: String, right: String): Boolean {
    val normalizedLeft = left.trim()
    val normalizedRight = right.trim()
    if (normalizedLeft.isBlank() || normalizedRight.isBlank()) return false
    return when (source.tagDialect) {
        TagDialect.UNDERSCORE -> normalizeGelbooruToken(normalizedLeft) == normalizeGelbooruToken(normalizedRight)
        TagDialect.SPACED -> normalizeMatchToken(normalizedLeft) == normalizeMatchToken(normalizedRight)
    }
}
