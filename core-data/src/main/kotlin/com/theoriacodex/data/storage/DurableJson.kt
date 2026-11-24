package com.theoriacodex.data.storage

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

@OptIn(ExperimentalSerializationApi::class)
val DurableJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    coerceInputValues = true
    isLenient = true
}

inline fun <reified T> List<JsonElement>.decodeEachOrSkip(): List<T> = mapNotNull { element ->
    try {
        DurableJson.decodeFromJsonElement<T>(element)
    } catch (_: SerializationException) {
        null
    }
}

fun JsonElement.storedVersion(key: String): Int? =
    ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.content?.toIntOrNull()
