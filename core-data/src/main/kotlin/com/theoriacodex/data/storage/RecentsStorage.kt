package com.theoriacodex.data.storage

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import com.theoriacodex.data.repository.RecentPostEntry
import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.DateRange
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey

const val CURRENT_QUERY_STORAGE_SCHEMA_VERSION = 1

data class QueryStorageRecord(
    @field:SerializedName("schemaVersion") val schemaVersion: Int? = null,
    @field:SerializedName("modeType") val modeType: String = "unified",
    @field:SerializedName("modeSource") val modeSource: String? = null,
    @field:SerializedName("includeTags") val includeTags: List<String> = emptyList(),
    @field:SerializedName("excludeTags") val excludeTags: List<String> = emptyList(),
    @field:SerializedName("sort") val sort: String = SortMode.TOP.name,
    @field:SerializedName("dateFromEpochMs") val dateFromEpochMs: Long? = null,
    @field:SerializedName("dateToEpochMs") val dateToEpochMs: Long? = null,
    @field:SerializedName("minScore") val minScore: Int? = null,
    @field:SerializedName("includeTerms") val includeTerms: List<SearchTermStorageRecord?>? = null,
    @field:SerializedName("excludeTerms") val excludeTerms: List<SearchTermStorageRecord?>? = null,
)

data class SearchTermStorageRecord(
    @field:SerializedName("value") val value: String? = null,
    @field:SerializedName("facet") val facet: String? = null,
    @field:SerializedName("sourceNamespace") val sourceNamespace: String? = null,
)

object QueryStorageCodec {
    fun encode(query: Query): QueryStorageRecord {
        val mode = query.mode
        return QueryStorageRecord(
            schemaVersion = CURRENT_QUERY_STORAGE_SCHEMA_VERSION,
            modeType = if (mode is QueryMode.Source) "source" else "unified",
            modeSource = (mode as? QueryMode.Source)?.source?.name,
            includeTags = query.includeTags,
            excludeTags = query.excludeTags,
            sort = query.sort.name,
            dateFromEpochMs = query.dateRange?.fromEpochMs,
            dateToEpochMs = query.dateRange?.toEpochMs,
            minScore = query.minScore,
            includeTerms = query.includeTerms.map(::encodeTerm),
            excludeTerms = query.excludeTerms.map(::encodeTerm),
        )
    }

    /** Missing version is the legacy v1 shape; newer versions fail closed. */
    fun decode(record: QueryStorageRecord): Query {
        require(record.schemaVersion == null || record.schemaVersion == CURRENT_QUERY_STORAGE_SCHEMA_VERSION) {
            "Unsupported stored Query version: ${record.schemaVersion}"
        }
        val mode = when (record.modeType) {
            "unified" -> QueryMode.Unified
            "source" -> record.modeSource.toSourceKeyOrNull()?.let(QueryMode::Source) ?: QueryMode.Unified
            else -> QueryMode.Unified
        }
        return Query(
            mode = mode,
            includeTerms = record.includeTerms?.mapNotNull { it?.toDomainOrNull() }
                ?: record.includeTags.map(::SearchTerm),
            excludeTerms = record.excludeTerms?.mapNotNull { it?.toDomainOrNull() }
                ?: record.excludeTags.map(::SearchTerm),
            sort = runCatching { SortMode.valueOf(record.sort) }.getOrDefault(SortMode.TOP),
            dateRange = if (record.dateFromEpochMs == null && record.dateToEpochMs == null) null else {
                DateRange(record.dateFromEpochMs, record.dateToEpochMs)
            },
            minScore = record.minScore,
        )
    }

    fun encodeJson(query: Query, gson: Gson = Gson()): String = gson.toJson(encode(query))

    fun decodeJson(payload: String, gson: Gson = Gson()): Query {
        val root = JsonParser.parseString(payload)
        require(root.isJsonObject) { "Stored Query root must be an object" }
        val record = gson.fromJson(root, QueryStorageRecord::class.java)
            ?: error("Stored Query decoded to null")
        require(record.schemaVersion == CURRENT_QUERY_STORAGE_SCHEMA_VERSION) {
            "Room Query payload requires schema version $CURRENT_QUERY_STORAGE_SCHEMA_VERSION"
        }
        return decode(record)
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

private fun String?.toSourceKeyOrNull(): SourceKey? =
    this?.trim()?.let { runCatching { SourceKey.valueOf(it) }.getOrNull() }

data class LegacyRecentsStoreFile(
    @field:SerializedName("watchedPosts") val watchedPosts: List<LegacyRecentPostRecord?>? = null,
    @field:SerializedName("searches") val searches: List<LegacyRecentSearchRecord?>? = null,
)

data class LegacyRecentPostRecord(
    @field:SerializedName("post") val post: PostStorageRecord? = null,
    @field:SerializedName("viewedAtEpochMs") val viewedAtEpochMs: Long? = null,
    @field:SerializedName("origin") val origin: String? = null,
    @field:SerializedName("originQueryHash") val originQueryHash: String? = null,
) {
    fun toDomainOrNull(): RecentPostEntry? {
        val decoded = post?.let(PostStorageCodec::decode) ?: return null
        val decodedOrigin = origin?.let { runCatching { ViewerStreamSource.valueOf(it) }.getOrNull() }
            ?: ViewerStreamSource.SEARCH
        return RecentPostEntry(
            post = decoded,
            viewedAtEpochMs = viewedAtEpochMs ?: 0L,
            origin = decodedOrigin,
            originQueryHash = originQueryHash?.takeIf(String::isNotBlank),
        )
    }
}

data class LegacyRecentSearchRecord(
    @field:SerializedName("query") val query: QueryStorageRecord? = null,
    @field:SerializedName("queryHash") val queryHash: String? = null,
    @field:SerializedName("searchedAtEpochMs") val searchedAtEpochMs: Long? = null,
) {
    fun toDomainOrNull(): RecentSearchEntry? {
        val normalizedHash = queryHash?.trim()?.takeIf(String::isNotBlank) ?: return null
        val decoded = query?.let { runCatching { QueryStorageCodec.decode(it) }.getOrNull() } ?: return null
        return RecentSearchEntry(decoded, normalizedHash, searchedAtEpochMs ?: 0L)
    }
}
