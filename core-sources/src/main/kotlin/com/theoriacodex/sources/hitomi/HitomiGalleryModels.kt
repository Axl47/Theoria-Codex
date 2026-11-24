package com.theoriacodex.sources.hitomi

import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.sources.common.asIntOrNull
import com.theoriacodex.sources.common.asStringOrNull
import java.util.Locale
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

internal data class ParsedHitomiFile(
    val hash: String,
    val name: String,
    val hasAvif: Boolean,
    val width: Int?,
    val height: Int?,
)

internal fun JsonObject.stringValue(name: String): String? = get(name).asStringOrNull()?.trim()?.takeIf(String::isNotBlank)

internal fun JsonObject.truthy(name: String): Boolean {
    val primitive = get(name) as? JsonPrimitive ?: return false
    return when {
        primitive is JsonNull -> false
        primitive.isString -> primitive.content.isNotBlank()
        else -> primitive.booleanOrNull ?: (primitive.asIntOrNull() != 0)
    }
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
        hasAvif = get("hasavif").asIntOrNull() == 1,
        width = get("width").asIntOrNull()?.takeIf { value -> value > 0 },
        height = get("height").asIntOrNull()?.takeIf { value -> value > 0 },
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
