package com.theoriacodex.domain.model

data class DateRange(
    val fromEpochMs: Long?,
    val toEpochMs: Long?,
)

enum class SearchFacet {
    TAG,
    ARTIST,
    CHARACTER,
    SERIES,
    GROUP,
    TYPE,
    LANGUAGE,
}

data class SearchTerm(
    val value: String,
    val facet: SearchFacet = SearchFacet.TAG,
    val sourceNamespace: String? = null,
) {
    val isPortableGeneralTag: Boolean
        get() = facet == SearchFacet.TAG && sourceNamespace == null
}

data class Query(
    val mode: QueryMode,
    val includeTerms: List<SearchTerm>,
    val excludeTerms: List<SearchTerm>,
    val sort: SortMode,
    val dateRange: DateRange?,
    val minScore: Int?,
) {
    val includeTags: List<String>
        get() = includeTerms.map(SearchTerm::value)

    val excludeTags: List<String>
        get() = excludeTerms.map(SearchTerm::value)

    constructor(
        mode: QueryMode,
        includeTags: Iterable<String>,
        excludeTags: Iterable<String>,
        sort: SortMode,
        dateRange: DateRange?,
        minScore: Int?,
    ) : this(
        mode = mode,
        includeTerms = includeTags.asGeneralSearchTerms(),
        excludeTerms = excludeTags.asGeneralSearchTerms(),
        sort = sort,
        dateRange = dateRange,
        minScore = minScore,
    )

    fun portableTermsForUnified(): Query {
        if (mode != QueryMode.Unified) return this
        return copy(
            includeTerms = includeTerms.filter(SearchTerm::isPortableGeneralTag),
            excludeTerms = excludeTerms.filter(SearchTerm::isPortableGeneralTag),
        )
    }
}

fun Iterable<String>.asGeneralSearchTerms(): List<SearchTerm> {
    return map { value -> SearchTerm(value = value) }
}

sealed interface QueryMode {
    data object Unified : QueryMode
    data class Source(val source: SourceKey) : QueryMode
}

enum class SortMode {
    NEWEST,
    POPULAR,
    TOP,
    RANDOM,
}
