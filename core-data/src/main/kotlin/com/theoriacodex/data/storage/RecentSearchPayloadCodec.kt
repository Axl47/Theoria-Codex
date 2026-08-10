package com.theoriacodex.data.storage

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.data.repository.RecentSearchKind
import com.theoriacodex.data.repository.defaultRecentSearchKind
import com.theoriacodex.data.repository.defaultRecentSearchSources
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SourceKey

private const val CURRENT_RECENT_SEARCH_PAYLOAD_VERSION = 1

private data class RecentSearchPayloadRecord(
    @field:SerializedName("recentSearchSchemaVersion") val schemaVersion: Int? = null,
    @field:SerializedName("query") val query: QueryStorageRecord? = null,
    @field:SerializedName("kind") val kind: String? = null,
    @field:SerializedName("sources") val sources: List<String>? = null,
)

data class RecentSearchPayload(
    val query: Query,
    val kind: RecentSearchKind,
    val sources: List<SourceKey>,
)

/** Backward-compatible payload stored in the existing Room recent-search query column. */
object RecentSearchPayloadCodec {
    fun encodeJson(entry: RecentSearchEntry, gson: Gson = Gson()): String = encodeJson(
        query = entry.query,
        kind = entry.kind,
        sources = entry.sources,
        gson = gson,
    )

    fun encodeJson(
        query: Query,
        kind: RecentSearchKind,
        sources: List<SourceKey>,
        gson: Gson = Gson(),
    ): String {
        val normalizedSources = sources.distinct()
        validate(query, kind, normalizedSources)
        return gson.toJson(
            RecentSearchPayloadRecord(
                schemaVersion = CURRENT_RECENT_SEARCH_PAYLOAD_VERSION,
                query = QueryStorageCodec.encode(query),
                kind = kind.name,
                sources = normalizedSources.map(SourceKey::name),
            )
        )
    }

    fun decodeJson(payload: String, gson: Gson = Gson()): RecentSearchPayload {
        val root = JsonParser.parseString(payload)
        require(root.isJsonObject) { "Stored recent search root must be an object" }
        if (!root.asJsonObject.has("recentSearchSchemaVersion")) {
            val query = QueryStorageCodec.decodeJson(payload, gson)
            return RecentSearchPayload(
                query = query,
                kind = query.defaultRecentSearchKind(),
                sources = query.defaultRecentSearchSources(),
            )
        }
        val record = gson.fromJson(root, RecentSearchPayloadRecord::class.java)
            ?: error("Stored recent search decoded to null")
        require(record.schemaVersion == CURRENT_RECENT_SEARCH_PAYLOAD_VERSION) {
            "Unsupported stored recent-search version: ${record.schemaVersion}"
        }
        val query = QueryStorageCodec.decode(requireNotNull(record.query) { "Stored recent search has no query" })
        val kind = record.kind?.let { runCatching { RecentSearchKind.valueOf(it) }.getOrNull() }
            ?: error("Stored recent search has an invalid kind")
        val rawSources = record.sources ?: error("Stored recent search has no sources")
        val sources = rawSources.map { raw ->
            runCatching { SourceKey.valueOf(raw) }.getOrElse {
                error("Stored recent search has an invalid source")
            }
        }
        require(sources == sources.distinct()) { "Stored recent search sources must be distinct" }
        validate(query, kind, sources)
        return RecentSearchPayload(query, kind, sources)
    }

    private fun validate(query: Query, kind: RecentSearchKind, sources: List<SourceKey>) {
        when (kind) {
            RecentSearchKind.SOURCE -> {
                val source = (query.mode as? QueryMode.Source)?.source
                    ?: error("Source recent search requires a source query")
                require(sources == listOf(source)) { "Source recent search must contain its query source" }
            }
            RecentSearchKind.UNIFIED -> require(query.mode == QueryMode.Unified) {
                "Unified recent search requires a Unified query"
            }
            RecentSearchKind.MULTI_SEARCH -> {
                require(query.mode == QueryMode.Unified) { "Multi-Search requires a Unified query" }
                require(sources.size >= 2) { "Multi-Search requires at least two sources" }
            }
            RecentSearchKind.FYP -> {
                require(query.mode == QueryMode.Unified) { "FYP requires a Unified query" }
                require(sources.isNotEmpty()) { "FYP requires at least one source" }
            }
        }
    }
}
