package com.theoriacodex.sources.common

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

// Readers for provider responses held as a kotlinx JSON tree: the counterparts of the Gson readers in
// SourceAdapterCommon. They keep Gson's leniency, so a field of the wrong type reads as null instead of throwing.

/** The primitive's text (numbers and booleans as written); null for JSON null, arrays, objects, and absent keys. */
internal fun JsonElement?.asStringOrNull(): String? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

/**
 * A JSON number as an Int, truncating any fraction the way Gson did, or a JSON string holding an integer.
 * Null for anything else, including numbers outside the Int range.
 */
internal fun JsonElement?.asIntOrNull(): Int? {
    val primitive = (this as? JsonPrimitive)?.takeUnless { it is JsonNull } ?: return null
    if (primitive.isString) return primitive.content.trim().toIntOrNull()
    return primitive.content.toIntOrNull()
        ?: primitive.content.toDoubleOrNull()
            ?.takeIf { value -> value >= Int.MIN_VALUE && value <= Int.MAX_VALUE }
            ?.toInt()
}
