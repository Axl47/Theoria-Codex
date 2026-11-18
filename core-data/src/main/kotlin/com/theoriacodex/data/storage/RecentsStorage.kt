package com.theoriacodex.data.storage

import com.theoriacodex.domain.model.DateRange
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SearchTermGroup
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val CURRENT_QUERY_STORAGE_SCHEMA_VERSION = 2

/** One stored Query, in query_store.json and in Room recent-search payloads. */
@Serializable
data class QueryStorageRecord(
    @SerialName("schemaVersion")
    val schemaVersion: Int? = null,
    @SerialName("modeType")
    val modeType: String = "unified",
    @SerialName("modeSource")
    val modeSource: String? = null,
    @SerialName("includeTags")
    val includeTags: List<String> = emptyList(),
    @SerialName("excludeTags")
    val excludeTags: List<String> = emptyList(),
    @SerialName("sort")
    val sort: String = SortMode.TOP.name,
    @SerialName("dateFromEpochMs")
    val dateFromEpochMs: Long? = null,
    @SerialName("dateToEpochMs")
    val dateToEpochMs: Long? = null,
    @SerialName("minScore")
    val minScore: Int? = null,
    @SerialName("includeTerms")
    val includeTerms: List<SearchTermStorageRecord?>? = null,
    @SerialName("excludeTerms")
    val excludeTerms: List<SearchTermStorageRecord?>? = null,
    @SerialName("includeTermGroups")
    val includeTermGroups: List<List<SearchTermStorageRecord?>?>? = null,
)

@Serializable
data class SearchTermStorageRecord(
    @SerialName("value")
    val value: String? = null,
    @SerialName("facet")
    val facet: String? = null,
    @SerialName("sourceNamespace")
    val sourceNamespace: String? = null,
)

object QueryStorageCodec {
    fun encode(query: Query): QueryStorageRecord {
        val mode = query.mode
        return QueryStorageRecord(
            schemaVersion = CURRENT_QUERY_STORAGE_SCHEMA_VERSION,
            modeType = if (mode is QueryMode.Source) "source" else "unified",
            modeSource = (mode as? QueryMode.Source)?.source?.id,
            includeTags = query.includeTags,
            excludeTags = query.excludeTags,
            sort = query.sort.name,
            dateFromEpochMs = query.dateRange?.fromEpochMs,
            dateToEpochMs = query.dateRange?.toEpochMs,
            minScore = query.minScore,
            includeTerms = query.includeTerms.map(::encodeTerm),
            excludeTerms = query.excludeTerms.map(::encodeTerm),
            includeTermGroups = query.effectiveIncludeTermGroups.map { group ->
                group.terms.map(::encodeTerm)
            },
        )
    }

    /** Missing version is the legacy v1 shape; newer versions fail closed. */
    fun decode(record: QueryStorageRecord): Query {
        require(record.schemaVersion == null || record.schemaVersion in 1..CURRENT_QUERY_STORAGE_SCHEMA_VERSION) {
            "Unsupported stored Query version: ${record.schemaVersion}"
        }
        val mode = when (record.modeType) {
            "unified" -> QueryMode.Unified
            "source" -> record.modeSource?.let(SourceKey::parse)?.let(QueryMode::Source) ?: QueryMode.Unified
            else -> QueryMode.Unified
        }
        val includeTerms = record.includeTerms?.mapNotNull { it?.toDomainOrNull() }
            ?: record.includeTags.map(::SearchTerm)
        val includeGroups = if (
            record.schemaVersion == CURRENT_QUERY_STORAGE_SCHEMA_VERSION &&
            !record.includeTermGroups.isNullOrEmpty()
        ) {
            record.includeTermGroups.orEmpty().mapNotNull { rawGroup ->
                rawGroup.orEmpty().mapNotNull { it?.toDomainOrNull() }
                    .takeIf(List<SearchTerm>::isNotEmpty)
                    ?.let(::SearchTermGroup)
            }
        } else {
            includeTerms.map(SearchTermGroup::single)
        }
        return Query(
            mode = mode,
            includeTerms = includeGroups.flatMap(SearchTermGroup::terms),
            excludeTerms = record.excludeTerms?.mapNotNull { it?.toDomainOrNull() }
                ?: record.excludeTags.map(::SearchTerm),
            sort = runCatching { SortMode.valueOf(record.sort) }.getOrDefault(SortMode.TOP),
            dateRange = if (record.dateFromEpochMs == null && record.dateToEpochMs == null) null else {
                DateRange(record.dateFromEpochMs, record.dateToEpochMs)
            },
            minScore = record.minScore,
            includeTermGroups = includeGroups,
        )
    }

    private fun encodeTerm(term: SearchTerm) = SearchTermStorageRecord(
        value = term.value,
        facet = term.facet.name,
        sourceNamespace = term.sourceNamespace,
    )
}

private fun SearchTermStorageRecord.toDomainOrNull(): SearchTerm? {
    val normalized = value?.trim()?.takeIf(String::isNotBlank) ?: return null
    val normalizedFacet = facet?.trim().orEmpty()
    val resolvedFacet = if (normalizedFacet.isBlank()) {
        SearchFacet.TAG
    } else {
        runCatching { SearchFacet.valueOf(normalizedFacet) }.getOrNull() ?: return null
    }
    return SearchTerm(normalized, resolvedFacet, sourceNamespace?.trim()?.takeIf(String::isNotBlank))
}
