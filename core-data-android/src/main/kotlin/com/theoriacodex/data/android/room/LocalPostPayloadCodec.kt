package com.theoriacodex.data.android.room

import com.theoriacodex.data.storage.CURRENT_POST_STORAGE_SCHEMA_VERSION
import com.theoriacodex.data.storage.DurableJson
import com.theoriacodex.data.storage.PostStorageCodec
import com.theoriacodex.data.storage.PostStorageRecord
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SourceKey
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

sealed class LocalPostPayloadException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

class UnsupportedLocalPostPayloadVersionException(
    val payloadVersion: Int,
) : LocalPostPayloadException("Unsupported local Post payload version: $payloadVersion")

class MalformedLocalPostPayloadException(
    val reason: String,
    cause: Throwable? = null,
) : LocalPostPayloadException("Malformed local Post payload: $reason", cause)

/**
 * Strict Room boundary around the storage-independent [PostStorageRecord].
 *
 * The shared codec owns the durable field mapping. Room additionally requires an explicit current
 * schema version, validates the current record shape, and verifies that the JSON identity matches
 * the row key. This prevents corrupt or future payloads from being mistaken for missing posts.
 *
 * Rows written by Gson escape `& = < > '` as `\u0026`-style sequences and kotlinx writes them raw, so the same post can
 * be stored as different bytes; compare decoded values, never payload strings.
 */
internal object LocalPostPayloadCodec {
    private val FULL_FIELDS = listOf(
        "fullUrl",
        "fullLocalPath",
        "fullMime",
        "fullProgressiveUrls",
        "fullIsAnimated",
    )

    fun encode(post: Post): String = encode(PostStorageCodec.encode(post))

    fun encode(record: PostStorageRecord): String = DurableJson.encodeToString(PostStorageRecord.serializer(), record)

    fun decode(entity: PostEntity): Post {
        val root = parseRoot(entity.payloadJson)
        val version = root.requiredInteger("schemaVersion")
        if (version != CURRENT_POST_STORAGE_SCHEMA_VERSION) {
            throw UnsupportedLocalPostPayloadVersionException(version)
        }
        validateCurrentShape(root)

        val record = try {
            DurableJson.decodeFromJsonElement(PostStorageRecord.serializer(), root)
        } catch (error: RuntimeException) {
            throw MalformedLocalPostPayloadException("record cannot be decoded", error)
        }
        val post = try {
            PostStorageCodec.decode(record)
        } catch (error: RuntimeException) {
            throw MalformedLocalPostPayloadException("record contains invalid field values", error)
        } ?: throw MalformedLocalPostPayloadException("record contains an invalid source or taxonomy value")

        val expectedId = PostId(
            source = entity.source.toSourceKey("database source"),
            sourcePostId = entity.sourcePostId,
        )
        if (post.id != expectedId) {
            throw MalformedLocalPostPayloadException(
                "record id ${post.id.source}:${post.id.sourcePostId} does not match " +
                    "database id ${expectedId.source}:${expectedId.sourcePostId}"
            )
        }
        return post
    }

    private fun parseRoot(raw: String): JsonObject {
        val root = try {
            DurableJson.parseToJsonElement(raw)
        } catch (error: RuntimeException) {
            throw MalformedLocalPostPayloadException("payload is not valid JSON", error)
        }
        return root as? JsonObject ?: throw MalformedLocalPostPayloadException("root must be an object")
    }

    private fun validateCurrentShape(root: JsonObject) {
        root.requiredNonBlankString("source")
        root.requiredNonBlankString("sourcePostId")
        root.requiredStringArray("previewProgressiveUrls")
        root.requiredBoolean("previewIsAnimated")
        root.requiredStringArray("canonicalTags")
        root.requiredStringArray("rawTags")
        root.requiredObjectArray("media", ::validateImageRef)
        root.requiredObjectArray("taxonomy", ::validateTaxonomy)
        root.requiredObjectArray("creatorProfiles", ::validateCreator)

        root.optionalObject("creatorProfile")?.let(::validateCreator)
        val hasFull = FULL_FIELDS.any(root::containsKey)
        if (hasFull) {
            root.requiredStringArray("fullProgressiveUrls")
            root.requiredBoolean("fullIsAnimated")
        }
    }

    private fun validateImageRef(value: JsonObject) {
        value.requiredStringArray("progressiveUrls")
        value.requiredBoolean("isAnimated")
    }

    private fun validateTaxonomy(value: JsonObject) {
        value.requiredNonBlankString("value")
        val facet = value.requiredNonBlankString("facet")
        if (runCatching { SearchFacet.valueOf(facet) }.getOrNull() == null) {
            throw MalformedLocalPostPayloadException("taxonomy facet is invalid")
        }
    }

    private fun validateCreator(value: JsonObject) {
        value.requiredNonBlankString("source").toSourceKey("creator source")
        value.requiredNonBlankString("displayName")
    }
}

/** A JSON string, number, or boolean; null is not a value here, as it was not under Gson's tree. */
private fun JsonElement.valueOrNull(): JsonPrimitive? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }

private fun JsonObject.required(field: String): JsonElement =
    get(field) ?: throw MalformedLocalPostPayloadException("$field is missing")

private fun JsonObject.requiredInteger(field: String): Int {
    val raw = required(field).valueOrNull()?.takeUnless(JsonPrimitive::isString)?.content
    if (raw == null || !raw.matches(INTEGER_PATTERN)) {
        throw MalformedLocalPostPayloadException("$field must be an integer")
    }
    return raw.toIntOrNull()
        ?: throw MalformedLocalPostPayloadException("$field is outside the integer range")
}

private fun JsonObject.requiredNonBlankString(field: String): String {
    val value = required(field).valueOrNull()?.takeIf(JsonPrimitive::isString)?.content
        ?: throw MalformedLocalPostPayloadException("$field must be a string")
    return value.takeIf(String::isNotBlank)
        ?: throw MalformedLocalPostPayloadException("$field must not be blank")
}

private fun JsonObject.requiredBoolean(field: String) {
    val value = required(field).valueOrNull()?.takeUnless(JsonPrimitive::isString)?.content
    if (value != "true" && value != "false") {
        throw MalformedLocalPostPayloadException("$field must be a boolean")
    }
}

private fun JsonObject.requiredStringArray(field: String) {
    requiredArray(field).forEachIndexed { index, element ->
        if (element.valueOrNull()?.isString != true) {
            throw MalformedLocalPostPayloadException("$field[$index] must be a string")
        }
    }
}

private inline fun JsonObject.requiredObjectArray(
    field: String,
    validate: (JsonObject) -> Unit,
) {
    requiredArray(field).forEachIndexed { index, element ->
        validate(element as? JsonObject ?: throw MalformedLocalPostPayloadException("$field[$index] must be an object"))
    }
}

private fun JsonObject.requiredArray(field: String): JsonArray =
    required(field) as? JsonArray ?: throw MalformedLocalPostPayloadException("$field must be an array")

private fun JsonObject.optionalObject(field: String): JsonObject? {
    val element = get(field) ?: return null
    return element as? JsonObject ?: throw MalformedLocalPostPayloadException("$field must be an object")
}

private fun String.toSourceKey(field: String): SourceKey {
    return SourceKey.parse(this)
        ?: throw MalformedLocalPostPayloadException("$field is invalid")
}

private val INTEGER_PATTERN = Regex("-?(0|[1-9][0-9]*)")
