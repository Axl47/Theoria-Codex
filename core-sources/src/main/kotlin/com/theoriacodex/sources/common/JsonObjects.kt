package com.theoriacodex.sources.common

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.net.URLEncoder

/** The object entries of this object's [name] array, in order; empty when the field is absent or not an array. */
internal fun JsonObject.objects(name: String): List<JsonObject> = optionalJsonArray(name).objects()

/** The object entries of this array, in order, skipping anything else; empty for null. */
internal fun JsonArray?.objects(): List<JsonObject> =
    elementsOrEmpty().mapNotNull { element -> element.takeIf(JsonElement::isJsonObject)?.asJsonObject }

/** UTF-8 percent-encoding for a URL path segment or query component, with spaces as `%20` rather than `+`. */
internal fun String.percentEncode(): String = URLEncoder.encode(this, Charsets.UTF_8.name()).replace("+", "%20")
