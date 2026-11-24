package com.theoriacodex.sources.common

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

internal fun JsonElement?.asStringOrNull(): String? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

internal fun JsonElement?.asIntOrNull(): Int? {
    val primitive = (this as? JsonPrimitive)?.takeUnless { it is JsonNull } ?: return null
    if (primitive.isString) return primitive.content.trim().toIntOrNull()
    return primitive.content.toIntOrNull()
        ?: primitive.content.toDoubleOrNull()
            ?.takeIf { value -> value >= Int.MIN_VALUE && value <= Int.MAX_VALUE }
            ?.toInt()
}
