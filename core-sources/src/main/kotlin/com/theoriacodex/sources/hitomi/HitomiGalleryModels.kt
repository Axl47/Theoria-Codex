package com.theoriacodex.sources.hitomi

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.theoriacodex.domain.adapter.SourceFailureReason
import java.util.Locale

internal data class ParsedHitomiFile(
    val hash: String,
    val name: String,
    val hasAvif: Boolean,
    val width: Int?,
    val height: Int?,
)

internal fun JsonObject.stringValue(name: String): String? {
    val element = get(name)?.takeUnless(JsonElement::isJsonNull) ?: return null
    return runCatching { element.asString.trim() }.getOrNull()?.takeIf(String::isNotBlank)
}

internal fun JsonObject.untrimmedStringValue(name: String): String? {
    val element = get(name)?.takeUnless(JsonElement::isJsonNull) ?: return null
    return runCatching { element.asString }.getOrNull()
}

internal fun JsonObject.intValue(name: String): Int? {
    val element = get(name)?.takeUnless(JsonElement::isJsonNull) ?: return null
    return runCatching { element.asInt }.getOrNull()
}

internal fun JsonObject.arrayValue(name: String): JsonArray? {
    return get(name)?.takeIf(JsonElement::isJsonArray)?.asJsonArray
}

internal fun JsonElement.asObjectOrNull(): JsonObject? {
    return takeIf(JsonElement::isJsonObject)?.asJsonObject
}

internal fun JsonObject.truthy(name: String): Boolean {
    val element = get(name)?.takeUnless(JsonElement::isJsonNull) ?: return false
    return runCatching {
        when {
            element.asJsonPrimitive.isBoolean -> element.asBoolean
            element.asJsonPrimitive.isNumber -> element.asInt != 0
            else -> element.asString.isNotBlank()
        }
    }.getOrDefault(false)
}

internal fun JsonObject.toHitomiMediaFile(): ParsedHitomiFile? {
    val hash = stringValue("hash") ?: return null
    val name = stringValue("name") ?: return null
    val extension = name.substringAfterLast('.', missingDelimiterValue = "")
    if (!HITOMI_FILE_HASH.matches(hash.lowercase(Locale.ROOT)) || !HITOMI_FILE_EXTENSION.matches(extension)) {
        return null
    }
    return ParsedHitomiFile(
        hash = hash,
        name = name,
        hasAvif = intValue("hasavif") == 1,
        width = intValue("width")?.takeIf { value -> value > 0 },
        height = intValue("height")?.takeIf { value -> value > 0 },
    )
}

internal data class HitomiSourceCacheSnapshot(
    val globalIndex: HitomiGlobalIndexCacheSnapshot,
    val membership: HitomiByteBudgetCacheSnapshot<String>,
    val random: HitomiRandomSnapshotCacheSnapshot,
    val knownSizes: HitomiByteBudgetCacheSnapshot<String>,
    val suggestionCounts: HitomiByteBudgetCacheSnapshot<String>,
)

internal class HitomiGalleryException(
    val reason: SourceFailureReason,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

private val HITOMI_FILE_HASH = Regex("[0-9a-f]{64}")
private val HITOMI_FILE_EXTENSION = Regex("[A-Za-z0-9]{1,10}")
