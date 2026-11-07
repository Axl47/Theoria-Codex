package com.theoriacodex.domain.tags

import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SourceKey

/**
 * The spelling Pixiv search receives for one tag. Booru-style (ASCII) tokens such as
 * `saber_(fate)` become `saber`, because Pixiv neither joins words with underscores nor uses
 * booru qualifiers. Native Pixiv tags such as `ラム(リゼロ)` pass through unchanged, because
 * their parentheses are part of the tag.
 */
fun pixivSearchToken(raw: String): String {
    val token = raw.trim().removePrefix("-").replace(PIXIV_WHITESPACE_REGEX, " ").trim()
    if (!token.all { it.code < ASCII_LIMIT }) return token
    var normalized = token.replace('_', ' ').replace(PIXIV_WHITESPACE_REGEX, " ").trim()
    while (normalized.isNotBlank() && BOORU_QUALIFIER_REGEX.containsMatchIn(normalized)) {
        normalized = normalized.replace(BOORU_QUALIFIER_REGEX, "").trim()
    }
    return normalized
}

/**
 * Upgrades a Pixiv post stored before taxonomy terms carried translations. The adapter always
 * wrote each translation directly after its native tag in `canonicalTags`, so that order
 * recovers the pairs: native terms gain their translation, and standalone translation terms
 * (which used to render as separate chips) are dropped. Other posts are returned unchanged.
 */
fun Post.withRecoveredPixivTranslations(): Post {
    if (id.source != SourceKey.PIXIV || taxonomy.any { it.translation != null }) return this
    val native = rawTags.mapTo(linkedSetOf()) { it.trim() }.apply { remove("") }
    if (native.isEmpty() || canonicalTags.size <= native.size) return this

    val translations = mutableMapOf<String, String>()
    var pendingNative: String? = null
    canonicalTags.forEach { raw ->
        val tag = raw.trim()
        val owner = pendingNative
        when {
            tag in native -> pendingNative = tag
            owner != null && tag.isNotBlank() -> {
                translations.putIfAbsent(owner, tag)
                pendingNative = null
            }
        }
    }
    if (translations.isEmpty()) return this

    val translatedValues = translations.values.toSet()
    return copy(
        taxonomy = taxonomy
            .filterNot { term -> term.value in translatedValues && term.value !in native }
            .map { term ->
                if (term.facet == SearchFacet.TAG) term.copy(translation = translations[term.value]) else term
            },
    )
}

private const val ASCII_LIMIT = 128
private val PIXIV_WHITESPACE_REGEX = Regex("\\s+")
private val BOORU_QUALIFIER_REGEX = Regex("\\s*\\([^)]*\\)\\s*$")
