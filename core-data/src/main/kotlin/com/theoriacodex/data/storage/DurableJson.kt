package com.theoriacodex.data.storage

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull

/**
 * The single kotlinx.serialization configuration for saved data (files, Room payloads, exports) and for the app's
 * fixed-shape network, asset, and developer-copy JSON. Each flag keeps data written by Gson, or read by an older
 * build, behaving as it did under the plain `Gson()` it replaces. Golden files that older builds wrote pin each
 * stored format (`assertStoredFormat`, core-data test fixtures).
 * Rationale and the full compatibility contract: .docs/exec/gson-to-kotlinx-serialization.html.
 */
@OptIn(ExperimentalSerializationApi::class)
val DurableJson: Json = Json {
    // Newer files and retired fields must not be rejected.
    ignoreUnknownKeys = true
    // Backup validation requires defaulted keys to be present, as Gson wrote them.
    encodeDefaults = true
    // Gson omits nulls, and some readers treat a present key as data.
    explicitNulls = false
    // A stored null on a defaulted field repairs to the default instead of resetting the store.
    coerceInputValues = true
    // Gson accepted quoted primitives such as "5" for an Int and "true" for a Boolean.
    isLenient = true
}

/**
 * Decodes each element of a JSON list as [T] and drops the ones that do not decode, so one malformed entry in a
 * network response or bundled asset never hides its well-formed neighbours.
 */
inline fun <reified T> List<JsonElement>.decodeEachOrSkip(): List<T> = mapNotNull { element ->
    try {
        DurableJson.decodeFromJsonElement<T>(element)
    } catch (_: SerializationException) {
        null
    }
}

/**
 * Reads the integer version stored under [key] at the top of a JSON document without decoding the rest; null when the
 * document is not an object or the value is absent or not an integer. Stores check it before their typed decode, so
 * a file a newer build wrote is refused, and left in place, whatever shape the rest of it has.
 */
fun JsonElement.storedVersion(key: String): Int? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.intOrNull
