package com.theoriacodex.data.storage

import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.data.repository.RecentSearchKind
import com.theoriacodex.data.repository.defaultRecentSearchKind
import com.theoriacodex.data.repository.defaultRecentSearchSources
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SourceKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

private const val CURRENT_RECENT_SEARCH_PAYLOAD_VERSION = 1

private const val RETIRED_FOR_YOU_KIND = "FYP"

@Serializable
internal data class RecentSearchPayloadRecord(
    @SerialName("recentSearchSchemaVersion") val recentSearchSchemaVersion: Int? = null,
    @SerialName("query") val query: QueryStorageRecord? = null,
    @SerialName("kind") val kind: String? = null,
    @SerialName("sources") val sources: List<String>? = null,
    @SerialName("sourceTags") val sourceTags: Map<String, List<String>>? = null,
)

data class RecentSearchPayload(
    val query: Query,
    val kind: RecentSearchKind,
    val sources: List<SourceKey>,
    val sourceTags: Map<SourceKey, List<String>> = emptyMap(),
)

object RecentSearchPayloadCodec {
    fun encodeJson(entry: RecentSearchEntry): String = encodeJson(
        query = entry.query,
        kind = entry.kind,
        sources = entry.sources,
        sourceTags = entry.sourceTags,
    )

    fun encodeJson(
        query: Query,
        kind: RecentSearchKind,
        sources: List<SourceKey>,
        sourceTags: Map<SourceKey, List<String>> = emptyMap(),
    ): String =
        DurableJson.encodeToString(RecentSearchPayloadRecord.serializer(), record(query, kind, sources, sourceTags))

    fun encode(entry: RecentSearchEntry): JsonElement = DurableJson.encodeToJsonElement(
        RecentSearchPayloadRecord.serializer(),
        record(entry.query, entry.kind, entry.sources, entry.sourceTags),
    )

    fun normalize(entry: RecentSearchEntry): RecentSearchEntry {
        val payload = decode(encode(entry))
        return entry.copy(query = payload.query, kind = payload.kind, sources = payload.sources, sourceTags = payload.sourceTags)
    }

    private fun record(
        query: Query,
        kind: RecentSearchKind,
        sources: List<SourceKey>,
        sourceTags: Map<SourceKey, List<String>>,
    ): RecentSearchPayloadRecord {
        val normalizedSources = sources.distinct()
        val normalizedSourceTags = normalizedSources.associateWith { source ->
            sourceTags[source].orEmpty().toList()
        }.filterValues(List<String>::isNotEmpty)
        validate(query, kind, normalizedSources, normalizedSourceTags)
        return RecentSearchPayloadRecord(
            recentSearchSchemaVersion = CURRENT_RECENT_SEARCH_PAYLOAD_VERSION,
            query = QueryStorageCodec.encode(query),
            kind = kind.name,
            sources = normalizedSources.map(SourceKey::id),
            sourceTags = normalizedSourceTags.mapKeys { (source, _) -> source.id },
        )
    }

    fun decodeJson(payload: String): RecentSearchPayload = decode(DurableJson.parseToJsonElement(payload))

    fun decode(root: JsonElement): RecentSearchPayload {
        require(root is JsonObject) { "Stored recent search root must be an object" }
        if (!root.containsKey("recentSearchSchemaVersion")) {
            val bare = DurableJson.decodeFromJsonElement(QueryStorageRecord.serializer(), root)
            require(bare.schemaVersion != null && bare.schemaVersion in 1..CURRENT_QUERY_STORAGE_SCHEMA_VERSION) {
                "Room Query payload requires a supported schema version"
            }
            val query = QueryStorageCodec.decode(bare)
            return RecentSearchPayload(
                query = query,
                kind = query.defaultRecentSearchKind(),
                sources = query.defaultRecentSearchSources(),
            )
        }
        val record = DurableJson.decodeFromJsonElement(RecentSearchPayloadRecord.serializer(), root)
        require(record.recentSearchSchemaVersion == CURRENT_RECENT_SEARCH_PAYLOAD_VERSION) {
            "Unsupported stored recent-search version: ${record.recentSearchSchemaVersion}"
        }
        val query = QueryStorageCodec.decode(requireNotNull(record.query) { "Stored recent search has no query" })
        val retired = record.kind == RETIRED_FOR_YOU_KIND
        val kind = record.kind?.let { stored ->
            if (retired) RecentSearchKind.UNIFIED
            else runCatching { RecentSearchKind.valueOf(stored) }.getOrNull()
        } ?: error("Stored recent search has an invalid kind")
        val rawSources = record.sources ?: error("Stored recent search has no sources")
        val sources = rawSources.map { raw ->
            SourceKey.parse(raw) ?: error("Stored recent search has an invalid source")
        }
        require(sources == sources.distinct()) { "Stored recent search sources must be distinct" }
        val storedTags = record.sourceTags.orEmpty().mapKeys { (rawSource, _) ->
            SourceKey.parse(rawSource) ?: error("Stored recent search has an invalid source-tag source")
        }
        val sourceTags = if (retired) sources.associateWith { storedTags[it].orEmpty() } else storedTags
        validate(query, kind, sources, sourceTags)
        return RecentSearchPayload(query, kind, sources, sourceTags)
    }

    private fun validate(
        query: Query,
        kind: RecentSearchKind,
        sources: List<SourceKey>,
        sourceTags: Map<SourceKey, List<String>> = emptyMap(),
    ) {
        require(sourceTags.keys.all { source -> source in sources }) {
            "Recent search source tags must belong to participating sources"
        }
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
        }
    }
}
