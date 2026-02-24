package com.theoriacodex.domain.model

data class DateRange(
    val fromEpochMs: Long?,
    val toEpochMs: Long?,
)

data class Query(
    val mode: QueryMode,
    val includeTags: List<String>,
    val excludeTags: List<String>,
    val sort: SortMode,
    val dateRange: DateRange?,
    val minScore: Int?,
)

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
