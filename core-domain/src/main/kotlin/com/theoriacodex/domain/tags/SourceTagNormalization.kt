package com.theoriacodex.domain.tags

import com.theoriacodex.domain.model.SourceKey

private val SOURCE_TAG_WHITESPACE_REGEX = Regex("\\s+")
private val HTML_ENTITY_REGEX = Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{2,6});")
private val NAMED_HTML_ENTITIES = mapOf(
    "amp" to '&'.code, "lt" to '<'.code, "gt" to '>'.code, "quot" to '"'.code, "apos" to '\''.code,
)

/** How a source spells multi-word tags. Persisted tag keys depend on it, so it never changes for a source. */
enum class TagDialect {
    /** Booru style: lowercase, words joined with underscores. */
    UNDERSCORE,

    /** Words joined with spaces; matching ignores case, underscores and repeated whitespace. */
    SPACED,
}

private val UNDERSCORE_BUILT_INS = setOf(
    SourceKey.GELBOORU, SourceKey.AIBOORU, SourceKey.DANBOORU, SourceKey.IWARA, SourceKey.RULE34XXX,
)

/** Every user-added site is a booru, so custom sources use [TagDialect.UNDERSCORE]. */
val SourceKey.tagDialect: TagDialect
    get() = if (isCustom || this in UNDERSCORE_BUILT_INS) TagDialect.UNDERSCORE else TagDialect.SPACED

/** The provider spelling of typed input, used as an autocomplete prefix. */
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
