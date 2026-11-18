package com.theoriacodex.data.storage

import com.theoriacodex.data.repository.MAX_SAVED_SEARCHES
import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.data.repository.SavedSearchEntry
import com.theoriacodex.data.repository.validateSavedSearchName
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

internal data class SavedSearchSnapshot(val entries: List<SavedSearchEntry>) {
    companion object {
        fun validated(entries: List<SavedSearchEntry>): SavedSearchSnapshot {
            require(entries.size <= MAX_SAVED_SEARCHES) { "Too many saved searches." }
            require(entries.map(SavedSearchEntry::id).distinct().size == entries.size) { "Duplicate saved-search IDs." }
            return SavedSearchSnapshot(entries.map { entry ->
                require(entry.id.isNotBlank() && entry.id.length <= 128) { "Invalid saved-search ID." }
                require(entry.search.queryHash.isNotBlank()) { "Saved search has no query identity." }
                require(entry.savedAtEpochMs >= 0 && entry.search.searchedAtEpochMs >= 0) { "Invalid saved-search date." }
                val payload = RecentSearchPayloadCodec.decodeJson(RecentSearchPayloadCodec.encodeJson(entry.search))
                entry.copy(
                    name = validateSavedSearchName(entry.name),
                    search = entry.search.copy(
                        query = payload.query,
                        kind = payload.kind,
                        sources = payload.sources,
                        sourceTags = payload.sourceTags,
                    ),
                )
            })
        }
    }
}

/** pinned_searches.json. Each execution is stored in the accepted-search replay format, [RecentSearchPayloadCodec]. */
@Serializable
private data class SavedSearchFileRecord(
    @SerialName("schemaVersion") val schemaVersion: Int,
    @SerialName("searches") val searches: List<SavedSearchRecord>,
)

@Serializable
private data class SavedSearchRecord(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    @SerialName("savedAtEpochMs") val savedAtEpochMs: Long,
    @SerialName("queryHash") val queryHash: String,
    @SerialName("searchedAtEpochMs") val searchedAtEpochMs: Long,
    @SerialName("execution") val execution: JsonElement,
)

/**
 * Reads and writes [SavedSearchSnapshot] as [SavedSearchFileRecord]. A file from a newer build throws
 * [UnsupportedStoreSchemaException] before its shape is read, so it is refused and left in place. Any invalid pin
 * fails the whole file as a [SerializationException], so the file store quarantines it rather than failing startup.
 */
internal object SavedSearchSnapshotSerializer : KSerializer<SavedSearchSnapshot> {
    override val descriptor: SerialDescriptor = SavedSearchFileRecord.serializer().descriptor

    override fun serialize(encoder: Encoder, value: SavedSearchSnapshot) {
        val searches = value.entries.map { entry ->
            SavedSearchRecord(
                id = entry.id,
                name = entry.name,
                savedAtEpochMs = entry.savedAtEpochMs,
                queryHash = entry.search.queryHash,
                searchedAtEpochMs = entry.search.searchedAtEpochMs,
                execution = RecentSearchPayloadCodec.encode(entry.search),
            )
        }
        encoder.encodeSerializableValue(SavedSearchFileRecord.serializer(), SavedSearchFileRecord(SAVED_SEARCH_SCHEMA_VERSION, searches))
    }

    override fun deserialize(decoder: Decoder): SavedSearchSnapshot {
        val tree = (decoder as JsonDecoder).decodeJsonElement()
        tree.storedVersion("schemaVersion")?.takeIf { it > SAVED_SEARCH_SCHEMA_VERSION }?.let { stored ->
            throw UnsupportedStoreSchemaException("Pinned searches", stored, SAVED_SEARCH_SCHEMA_VERSION)
        }
        val file = DurableJson.decodeFromJsonElement<SavedSearchFileRecord>(tree)
        return try {
            require(file.schemaVersion == SAVED_SEARCH_SCHEMA_VERSION) { "Unsupported saved-search version." }
            require(file.searches.size <= MAX_SAVED_SEARCHES) { "Too many saved searches." }
            SavedSearchSnapshot.validated(file.searches.map { record ->
                val payload = RecentSearchPayloadCodec.decode(record.execution)
                SavedSearchEntry(
                    id = record.id,
                    name = record.name,
                    savedAtEpochMs = record.savedAtEpochMs,
                    search = RecentSearchEntry(
                        query = payload.query,
                        queryHash = record.queryHash,
                        searchedAtEpochMs = record.searchedAtEpochMs,
                        kind = payload.kind,
                        sources = payload.sources,
                        sourceTags = payload.sourceTags,
                    ),
                )
            })
        } catch (invalid: RuntimeException) {
            throw SerializationException("Invalid saved-search library", invalid)
        }
    }
}

private const val SAVED_SEARCH_SCHEMA_VERSION = 1
