package com.theoriacodex.data.android.room

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.theoriacodex.data.storage.CURRENT_POST_STORAGE_SCHEMA_VERSION
import com.theoriacodex.data.storage.PostStorageCodec
import com.theoriacodex.data.storage.PostStorageRecord
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SourceKey

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
 */
internal class LocalPostPayloadCodec(
    private val gson: Gson,
) {
    fun encode(post: Post): String = gson.toJson(PostStorageCodec.encode(post))

    fun decode(entity: PostEntity): Post {
        val root = parseRoot(entity.payloadJson)
        val version = root.requiredInteger("schemaVersion")
        if (version != CURRENT_POST_STORAGE_SCHEMA_VERSION) {
            throw UnsupportedLocalPostPayloadVersionException(version)
        }
        validateCurrentShape(root)

        val record = try {
            gson.fromJson(root, PostStorageRecord::class.java)
        } catch (error: RuntimeException) {
            throw MalformedLocalPostPayloadException("record cannot be decoded", error)
        } ?: throw MalformedLocalPostPayloadException("record decoded to null")
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
            JsonParser.parseString(raw)
        } catch (error: RuntimeException) {
            throw MalformedLocalPostPayloadException("payload is not valid JSON", error)
        }
        if (!root.isJsonObject) {
            throw MalformedLocalPostPayloadException("root must be an object")
        }
        return root.asJsonObject
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
        val hasFull = FULL_FIELDS.any(root::has)
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

    companion object {
        private val FULL_FIELDS = listOf(
            "fullUrl",
            "fullLocalPath",
            "fullMime",
            "fullProgressiveUrls",
            "fullIsAnimated",
        )
    }
}

private fun JsonObject.requiredInteger(field: String): Int {
    val element = get(field)
        ?: throw MalformedLocalPostPayloadException("$field is missing")
    if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) {
        throw MalformedLocalPostPayloadException("$field must be an integer")
    }
    val raw = element.asJsonPrimitive.asString
    if (!raw.matches(INTEGER_PATTERN)) {
        throw MalformedLocalPostPayloadException("$field must be an integer")
    }
    return raw.toIntOrNull()
        ?: throw MalformedLocalPostPayloadException("$field is outside the integer range")
}

private fun JsonObject.requiredNonBlankString(field: String): String {
    val element = get(field)
        ?: throw MalformedLocalPostPayloadException("$field is missing")
    if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
        throw MalformedLocalPostPayloadException("$field must be a string")
    }
    return element.asString.takeIf(String::isNotBlank)
        ?: throw MalformedLocalPostPayloadException("$field must not be blank")
}

private fun JsonObject.requiredBoolean(field: String) {
    val element = get(field)
        ?: throw MalformedLocalPostPayloadException("$field is missing")
    if (!element.isJsonPrimitive || !element.asJsonPrimitive.isBoolean) {
        throw MalformedLocalPostPayloadException("$field must be a boolean")
    }
}

private fun JsonObject.requiredStringArray(field: String) {
    val array = requiredArray(field)
    array.forEachIndexed { index, element ->
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
            throw MalformedLocalPostPayloadException("$field[$index] must be a string")
        }
    }
}

private inline fun JsonObject.requiredObjectArray(
    field: String,
    validate: (JsonObject) -> Unit,
) {
    requiredArray(field).forEachIndexed { index, element ->
        if (!element.isJsonObject) {
            throw MalformedLocalPostPayloadException("$field[$index] must be an object")
        }
        validate(element.asJsonObject)
    }
}

private fun JsonObject.requiredArray(field: String): List<JsonElement> {
    val element = get(field)
        ?: throw MalformedLocalPostPayloadException("$field is missing")
    if (!element.isJsonArray) {
        throw MalformedLocalPostPayloadException("$field must be an array")
    }
    return element.asJsonArray.toList()
}

private fun JsonObject.optionalObject(field: String): JsonObject? {
    val element = get(field) ?: return null
    if (!element.isJsonObject) {
        throw MalformedLocalPostPayloadException("$field must be an object")
    }
    return element.asJsonObject
}

private fun String.toSourceKey(field: String): SourceKey {
    return runCatching { SourceKey.valueOf(this) }.getOrNull()
        ?: throw MalformedLocalPostPayloadException("$field is invalid")
}

private val INTEGER_PATTERN = Regex("-?(0|[1-9][0-9]*)")
