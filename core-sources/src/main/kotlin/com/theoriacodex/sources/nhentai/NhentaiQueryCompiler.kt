package com.theoriacodex.sources.nhentai

import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SortMode

internal fun compileNhentaiQuery(query: Query): String {
    val include = query.includeTerms.mapNotNull(SearchTerm::compileNhentaiTerm)
    val exclude = query.excludeTerms
        .mapNotNull(SearchTerm::compileNhentaiTerm)
        .map { compiled -> "-$compiled" }
    return (include + exclude).take(MAX_NHENTAI_QUERY_TERMS).joinToString(" ")
}

private fun SearchTerm.compileNhentaiTerm(): String? {
    val normalizedValue = normalizeNhentaiTag(value).takeIf(String::isNotBlank) ?: return null
    val namespace = resolvedNhentaiNamespace() ?: return normalizedValue.takeIf {
        facet == SearchFacet.TAG && sourceNamespace == null
    }
    return "$namespace:${normalizedValue.quotedNhentaiValue()}"
}

private fun SearchTerm.resolvedNhentaiNamespace(): String? {
    val expected = when (facet) {
        SearchFacet.TAG -> NHENTAI_TAG_NAMESPACE
        SearchFacet.ARTIST -> NHENTAI_ARTIST_NAMESPACE
        SearchFacet.CHARACTER -> NHENTAI_CHARACTER_NAMESPACE
        SearchFacet.SERIES -> NHENTAI_SERIES_NAMESPACE
        SearchFacet.GROUP -> NHENTAI_GROUP_NAMESPACE
        SearchFacet.TYPE -> NHENTAI_TYPE_NAMESPACE
        SearchFacet.LANGUAGE -> NHENTAI_LANGUAGE_NAMESPACE
    }
    val explicit = sourceNamespace?.trim()?.lowercase()
    return when {
        explicit == null && facet == SearchFacet.TAG -> null
        explicit == null -> expected
        explicit == expected -> expected
        else -> null
    }
}

private fun String.quotedNhentaiValue(): String {
    val escaped = replace("\\", "\\\\").replace("\"", "\\\"")
    return if (any(Char::isWhitespace)) "\"$escaped\"" else escaped
}

internal fun normalizeNhentaiTag(value: String): String {
    return value
        .trim()
        .removePrefix("-")
        .replace('_', ' ')
        .replace(NHENTAI_WHITESPACE_REGEX, " ")
        .trim()
}

internal fun Query.directNhentaiGalleryIdCandidate(): String? {
    if (excludeTerms.isNotEmpty()) return null
    val searchable = includeTerms.filterNot(SearchTerm::isNhentaiDirectLookupFilter)
    if (searchable.size != 1) return null
    val candidate = searchable.single()
    if (!candidate.isPortableGeneralTag) return null
    return candidate.value.trim().takeIf { value -> value.isNotBlank() && value.all(Char::isDigit) }
}

internal fun Query.singleIncludeTagCandidate(): String? {
    if (excludeTerms.isNotEmpty() || includeTerms.size != 1) return null
    val term = includeTerms.single()
    if (!term.isPortableGeneralTag) return null
    return normalizeNhentaiTag(term.value).takeIf(String::isNotBlank)
}

private fun SearchTerm.isNhentaiDirectLookupFilter(): Boolean {
    val normalized = normalizeNhentaiFilterTag(value)
    val isLanguage = normalized in NHENTAI_LANGUAGE_FILTER_TAGS && (
        isPortableGeneralTag ||
            (facet == SearchFacet.LANGUAGE && sourceNamespace in setOf(null, NHENTAI_LANGUAGE_NAMESPACE))
        )
    val isFullColor = normalized == NHENTAI_FULL_COLOR_TAG && (
        isPortableGeneralTag ||
            (facet == SearchFacet.TAG && sourceNamespace == NHENTAI_TAG_NAMESPACE)
        )
    return isLanguage || isFullColor
}

private fun normalizeNhentaiFilterTag(value: String): String {
    return value.trim().lowercase().replace('_', ' ').replace(NHENTAI_WHITESPACE_REGEX, " ")
}

internal fun mapSortParam(sortMode: SortMode): String? = when (sortMode) {
    SortMode.NEWEST -> null
    SortMode.POPULAR -> "popular-today"
    SortMode.TOP -> "popular-week"
    SortMode.RANDOM -> null
}

internal fun mapTaggedSortParam(sortMode: SortMode): String? = when (sortMode) {
    SortMode.NEWEST -> "date"
    SortMode.POPULAR -> "popular-today"
    SortMode.TOP -> "popular-week"
    SortMode.RANDOM -> null
}

internal fun mapMirrorSortParam(sortMode: SortMode): String? = when (sortMode) {
    SortMode.NEWEST -> "date"
    SortMode.POPULAR -> "popular-today"
    SortMode.TOP -> "popular-week"
    SortMode.RANDOM -> "date"
}

internal const val NHENTAI_FULL_COLOR_TAG = "full color"
internal const val NHENTAI_TAG_NAMESPACE = "tag"
internal const val NHENTAI_ARTIST_NAMESPACE = "artist"
internal const val NHENTAI_CHARACTER_NAMESPACE = "character"
internal const val NHENTAI_SERIES_NAMESPACE = "parody"
internal const val NHENTAI_GROUP_NAMESPACE = "group"
internal const val NHENTAI_TYPE_NAMESPACE = "category"
internal const val NHENTAI_LANGUAGE_NAMESPACE = "language"
internal val NHENTAI_LANGUAGE_FILTER_TAGS = setOf("english", "chinese", "japanese")
internal val NHENTAI_WHITESPACE_REGEX = Regex("\\s+")
private const val MAX_NHENTAI_QUERY_TERMS = 40
