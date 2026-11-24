package com.theoriacodex.sources.common

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.net.URLEncoder

internal fun JsonObject.objects(name: String): List<JsonObject> = optionalJsonArray(name).objects()

internal fun JsonArray?.objects(): List<JsonObject> =
    elementsOrEmpty().mapNotNull { element -> element.takeIf(JsonElement::isJsonObject)?.asJsonObject }

internal fun String.percentEncode(): String = URLEncoder.encode(this, Charsets.UTF_8.name()).replace("+", "%20")
