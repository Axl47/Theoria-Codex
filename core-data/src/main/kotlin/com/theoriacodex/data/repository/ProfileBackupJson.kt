package com.theoriacodex.data.repository

import com.theoriacodex.data.storage.DurableJson
import com.theoriacodex.data.storage.PostStorageCodec
import com.theoriacodex.data.storage.PostStorageRecord
import com.theoriacodex.data.storage.RecentSearchPayloadCodec
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SourceKey
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.put

/*
 * Strict readers for backup and restore-journal documents. Each check matches what the Gson tree accepted,
 * so a malformed field still fails with the same message.
 */

/**
 * Parses a backup document's root object. kotlinx reports malformed JSON as an [IllegalArgumentException], whose
 * message the backup screen shows verbatim; Gson's parse errors were not, so they keep reaching its generic message.
 */
internal fun parseBackupObject(raw: String): JsonObject = rejectMalformedBackup {
    DurableJson.parseToJsonElement(raw) as? JsonObject ?: throw SerializationException("Backup root is not an object")
}

/** Runs [decode], reporting a [SerializationException] as a malformed backup instead of a user-facing message. */
internal inline fun <T> rejectMalformedBackup(decode: () -> T): T = try {
    decode()
} catch (malformed: SerializationException) {
    throw IllegalStateException("Malformed backup", malformed)
}

internal fun <T> backupArray(values: List<T>, encode: (T) -> JsonElement) = JsonArray(values.map(encode))

internal fun JsonObject.backupString(name: String): String {
    val value = get(name)
    require(value is JsonPrimitive && value.isString) { "Invalid backup field: $name" }
    return value.content.also { require(it.isNotBlank() && it.length <= 32_768) { "Invalid backup field: $name" } }
}

internal fun JsonObject.backupLong(name: String): Long {
    val value = get(name)
    require(value.isBackupNumber()) { "Invalid backup field: $name" }
    return (value as JsonPrimitive).content.toLongOrNull()?.takeIf { it >= 0L }
        ?: error("Invalid backup integer: $name")
}

internal fun JsonObject.backupInt(name: String): Int = backupLong(name).also {
    require(it <= Int.MAX_VALUE) { "Backup integer is too large: $name" }
}.toInt()

internal fun JsonObject.backupObjects(name: String, limit: Int = 100_000): List<JsonObject> {
    val value = get(name)
    require(value is JsonArray && value.size <= limit) { "Invalid backup list: $name" }
    return value.map { element ->
        require(element is JsonObject) { "Invalid backup record: $name" }
        element
    }
}

internal fun JsonObject.backupStrings(name: String): List<String> {
    val value = get(name)
    require(value is JsonArray && value.size <= 10_000) { "Invalid backup list: $name" }
    return value.map {
        require(it is JsonPrimitive && it.isString && it.content.length <= 32_768)
        it.content
    }
}

/**
 * A nested object that must be present. A value of another type fails as Gson's cast did, outside
 * [IllegalArgumentException], so the backup screen shows its generic message.
 */
internal fun JsonObject.backupObject(name: String): JsonObject {
    val value = requireNotNull(get(name)) { "Invalid backup record: $name" }
    return value as? JsonObject ?: throw IllegalStateException("Invalid backup record: $name")
}

/** Matches Gson's `isNumber`: an unquoted numeric literal, not a string, boolean, or null. */
internal fun JsonElement?.isBackupNumber(): Boolean =
    this is JsonPrimitive && !isString && this !is JsonNull && content.toDoubleOrNull() != null

/** Matches Gson's `isBoolean`: an unquoted true or false. */
internal fun JsonElement?.isBackupBoolean(): Boolean = this is JsonPrimitive && !isString && booleanOrNull != null

internal fun JsonObjectBuilder.putId(id: PostId) {
    put("source", id.source.id)
    put("postId", id.sourcePostId)
}

internal fun JsonObject.backupId() = PostId(SourceKey.parseOrThrow(backupString("source")), backupString("postId"))

internal fun backupSearch(entry: RecentSearchEntry) = JsonObject(mapOf(
    "queryHash" to JsonPrimitive(entry.queryHash),
    "searchedAt" to JsonPrimitive(entry.searchedAtEpochMs),
    "payload" to RecentSearchPayloadCodec.encode(entry),
))

internal fun JsonObject.readBackupSearch(): RecentSearchEntry {
    val payload = RecentSearchPayloadCodec.decode(requireNotNull(get("payload")))
    return RecentSearchEntry(payload.query, backupString("queryHash"), backupLong("searchedAt"),
        payload.kind, payload.sources, payload.sourceTags)
}

internal fun encodeBackupPost(post: Post): JsonElement =
    DurableJson.encodeToJsonElement(PostStorageRecord.serializer(), PostStorageCodec.encode(portablePostSnapshot(post)))

internal fun decodeBackupPost(record: JsonObject): Post {
    require(record.backupInt("schemaVersion") == 1) { "Unsupported backup post version" }
    validateBackupPostRecord(record)
    val post = PostStorageCodec.decode(DurableJson.decodeFromJsonElement(PostStorageRecord.serializer(), record))
        ?: error("Invalid backup post")
    require(post.id.sourcePostId.isNotBlank()) { "Backup post has no identity" }
    return portablePostSnapshot(post)
}

private fun validateBackupPostRecord(record: JsonObject) {
    SourceKey.parseOrThrow(record.backupString("source"))
    record.backupString("sourcePostId")
    record.backupStrings("canonicalTags")
    record.backupStrings("rawTags")
    record.backupStrings("previewProgressiveUrls")
    record.backupObjects("media", 10_000).forEach { image ->
        image.backupStrings("progressiveUrls")
        require(image["isAnimated"].isBackupBoolean())
        image.backupObjects("videoVariants", 100).forEach { it.backupString("url") }
    }
    record.backupObjects("taxonomy", 10_000).forEach { term ->
        term.backupString("value")
        SearchFacet.valueOf(term.backupString("facet"))
    }
    record.backupObjects("creatorProfiles", 1_000).forEach(::validateBackupCreator)
    record["creatorProfile"]?.let {
        require(it is JsonObject)
        validateBackupCreator(it)
    }
}

private fun validateBackupCreator(record: JsonObject) {
    SourceKey.parseOrThrow(record.backupString("source"))
    val name = record["displayName"]
    require(name is JsonPrimitive && name.isString)
}
