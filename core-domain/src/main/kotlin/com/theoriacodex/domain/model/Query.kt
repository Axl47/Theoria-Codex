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
    val includeTerms: List<SearchTerm>,
    val excludeTerms: List<SearchTerm>,
    val sort: SortMode,
    val dateRange: DateRange?,
    val minScore: Int?,
    val includeTermGroups: List<SearchTermGroup> = includeTerms.map(SearchTermGroup::single),
) {
    val effectiveIncludeTermGroups: List<SearchTermGroup>
        get() = includeTermGroups.takeIf { groups ->
            groups.flatMap(SearchTermGroup::terms) == includeTerms
        } ?: includeTerms.map(SearchTermGroup::single)

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
        return withIncludeTermGroups(
            effectiveIncludeTermGroups.filter(SearchTermGroup::isPortableGeneralTagGroup),
        ).copy(
            excludeTerms = excludeTerms.filter(SearchTerm::isPortableGeneralTag),
        )
    }

    fun withIncludeTermGroups(groups: List<SearchTermGroup>): Query {
        return copy(
            includeTerms = groups.flatMap(SearchTermGroup::terms),
            includeTermGroups = groups,
        )
    }

    fun withIncludeTermsAsRequired(terms: List<SearchTerm>): Query {
        return withIncludeTermGroups(terms.map(SearchTermGroup::single))
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
