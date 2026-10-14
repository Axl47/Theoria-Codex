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

data class SearchTermGroup(
    val terms: List<SearchTerm>,
) {
    init {
        require(terms.isNotEmpty()) { "A Search term group must contain at least one term" }
        val first = terms.first()
        require(terms.all { term ->
            term.facet == first.facet && term.sourceNamespace == first.sourceNamespace
        }) {
            "Search term alternatives must share one facet and source namespace"
        }
    }

    val isPortableGeneralTagGroup: Boolean
        get() = terms.all(SearchTerm::isPortableGeneralTag)

    companion object {
        fun single(term: SearchTerm): SearchTermGroup = SearchTermGroup(listOf(term))
    }
}

data class Query(
    val mode: QueryMode,
    val includeTermGroups: List<SearchTermGroup>,
    val excludeTerms: List<SearchTerm>,
    val sort: SortMode,
    val dateRange: DateRange?,
    val minScore: Int?,
) {
    // Cached projection is derived during construction and cannot be changed by copy independently.
    val includeTerms: List<SearchTerm> = includeTermGroups.flatMap(SearchTermGroup::terms)
    val effectiveIncludeTermGroups: List<SearchTermGroup> get() = includeTermGroups
    val includeTags: List<String> get() = includeTerms.map(SearchTerm::value)
    val excludeTags: List<String> get() = excludeTerms.map(SearchTerm::value)

    /** Legacy flat construction remains explicit; supplied groups must agree with its flat input. */
    constructor(mode: QueryMode, includeTerms: Iterable<SearchTerm>, excludeTerms: List<SearchTerm>,
        sort: SortMode, dateRange: DateRange?, minScore: Int?, includeTermGroups: List<SearchTermGroup>? = null) :
        this(mode, canonicalIncludeGroups(includeTerms.toList(), includeTermGroups), excludeTerms, sort, dateRange, minScore)

    constructor(mode: QueryMode, includeTags: Iterable<String>, excludeTags: Iterable<String>,
        sort: SortMode, dateRange: DateRange?, minScore: Int?) :
        this(mode, includeTags.asGeneralSearchTerms(), excludeTags.asGeneralSearchTerms(), sort, dateRange, minScore)

    fun portableTermsForUnified(): Query = if (mode == QueryMode.Unified) normalizedForMode(mode).query else this
    fun withIncludeTermGroups(groups: List<SearchTermGroup>): Query = copy(includeTermGroups = groups)
    fun withIncludeTermsAsRequired(terms: List<SearchTerm>): Query = withIncludeTermGroups(terms.map(SearchTermGroup::single))
}

private fun canonicalIncludeGroups(terms: List<SearchTerm>, groups: List<SearchTermGroup>?): List<SearchTermGroup> {
    if (groups == null) return terms.map(SearchTermGroup::single)
    require(groups.flatMap(SearchTermGroup::terms) == terms) { "Query groups must agree with legacy flat terms" }
    return groups
}

data class QueryNormalization(val query: Query, val removedSourceOwnedTerms: Boolean)

/** One portability policy is shared by draft editing and provider execution. */
fun Query.normalizedForMode(mode: QueryMode): QueryNormalization {
    if (mode != QueryMode.Unified) return QueryNormalization(copy(mode = mode), false)
    val groups = includeTermGroups.filter(SearchTermGroup::isPortableGeneralTagGroup)
    val excluded = excludeTerms.filter(SearchTerm::isPortableGeneralTag)
    return QueryNormalization(copy(mode = mode, includeTermGroups = groups, excludeTerms = excluded),
        groups.size != includeTermGroups.size || excluded.size != excludeTerms.size)
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
