package com.theoriacodex.domain.tags

import com.theoriacodex.domain.model.SourceKey

private val SOURCE_TAG_WHITESPACE_REGEX = Regex("\\s+")
private val HTML_ENTITY_REGEX = Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{2,6});")
private val NAMED_HTML_ENTITIES = mapOf(
    "amp" to '&'.code, "lt" to '<'.code, "gt" to '>'.code, "quot" to '"'.code, "apos" to '\''.code,
)

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

/** Underscore-joined booru spelling; entities are decoded so escaped API tags compare equal. */
fun normalizeGelbooruToken(value: String): String {
    return decodeHtmlEntities(value)
        .trim()
        .lowercase()
        .replace(SOURCE_TAG_WHITESPACE_REGEX, "_")
}

/**
 * Decodes HTML character references (`&#039;`, `&#x27;`, `&amp;`, ...). Gelbooru-family APIs
 * return tag names escaped (`jojo&#039;s_bizarre_adventure`), but search and tag lookups want the
 * real name. A single pass, as in HTML, so `&amp;#039;` becomes the literal text `&#039;`.
 */
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
