package com.theoriacodex.data.storage

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import com.theoriacodex.data.repository.MAX_SAVED_SEARCHES
import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.data.repository.SavedSearchEntry
import com.theoriacodex.data.repository.validateSavedSearchName

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

/** Explicit wire keys avoid reflection and reuse the exact accepted-search replay codec. */
internal class SavedSearchSnapshotAdapter : TypeAdapter<SavedSearchSnapshot>() {
    override fun write(writer: JsonWriter, snapshot: SavedSearchSnapshot) {
        val root = JsonObject().apply {
            addProperty("schemaVersion", 1)
            add("searches", JsonArray().apply {
                snapshot.entries.forEach { entry ->
                    add(JsonObject().apply {
                        addProperty("id", entry.id)
                        addProperty("name", entry.name)
                        addProperty("savedAtEpochMs", entry.savedAtEpochMs)
                        addProperty("queryHash", entry.search.queryHash)
                        addProperty("searchedAtEpochMs", entry.search.searchedAtEpochMs)
                        add("execution", JsonParser.parseString(RecentSearchPayloadCodec.encodeJson(entry.search)))
                    })
                }
            })
        }
        writer.jsonValue(root.toString())
    }

    override fun read(reader: JsonReader): SavedSearchSnapshot = try {
        val root = JsonParser.parseReader(reader).asJsonObject
        require(root.get("schemaVersion").asInt == 1) { "Unsupported saved-search version." }
        val records = root.getAsJsonArray("searches")
        require(records.size() <= MAX_SAVED_SEARCHES) { "Too many saved searches." }
        SavedSearchSnapshot.validated(records.map { element ->
            val record = element.asJsonObject
            val payload = RecentSearchPayloadCodec.decodeJson(record.get("execution").toString())
            SavedSearchEntry(
                id = record.get("id").asString,
                name = record.get("name").asString,
                savedAtEpochMs = record.get("savedAtEpochMs").asLong,
                search = RecentSearchEntry(
                    query = payload.query,
                    queryHash = record.get("queryHash").asString,
                    searchedAtEpochMs = record.get("searchedAtEpochMs").asLong,
                    kind = payload.kind,
                    sources = payload.sources,
                    sourceTags = payload.sourceTags,
                ),
            )
        })
    } catch (failure: RuntimeException) {
        throw JsonParseException("Invalid saved-search library", failure)
    }
}
