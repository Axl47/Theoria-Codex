package com.theoriacodex.sources.common

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.theoriacodex.domain.adapter.QuickQueryKind
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.model.DateRange
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.media.inferMimeFromUrl
import com.theoriacodex.sources.media.mimeFromFileExt
import java.io.IOException

internal fun sourceQuickQuery(
    source: SourceKey,
    kind: QuickQueryKind,
    randomSort: SortMode = SortMode.NEWEST,
    nowEpochMs: Long = System.currentTimeMillis(),
): Query {
    val dayMs = 24L * 60L * 60L * 1000L
    val sort = when (kind) {
        QuickQueryKind.POPULAR_TODAY -> SortMode.POPULAR
        QuickQueryKind.TOP_7D -> SortMode.TOP
        QuickQueryKind.TOP_30D -> SortMode.TOP
        QuickQueryKind.NEWEST -> SortMode.NEWEST
        QuickQueryKind.RANDOM -> randomSort
    }
    val dateRange = when (kind) {
        QuickQueryKind.POPULAR_TODAY -> DateRange(nowEpochMs - dayMs, nowEpochMs)
        QuickQueryKind.TOP_7D -> DateRange(nowEpochMs - 7L * dayMs, nowEpochMs)
        QuickQueryKind.TOP_30D -> DateRange(nowEpochMs - 30L * dayMs, nowEpochMs)
        QuickQueryKind.NEWEST, QuickQueryKind.RANDOM -> null
    }
    return Query(
        mode = QueryMode.Source(source),
        includeTags = emptyList(),
        excludeTags = emptyList(),
        sort = sort,
        dateRange = dateRange,
        minScore = null,
    )
}

internal fun parseJsonArray(body: String, gson: Gson, errorLabel: String): JsonArray {
    return runCatching { gson.fromJson(body, JsonArray::class.java) }.getOrNull()
        ?: throw SourceAdapterException(
            reason = SourceFailureReason.PARSE,
            message = "$errorLabel returned malformed JSON array",
        )
}

internal fun parseJsonObject(body: String, gson: Gson, errorLabel: String): JsonObject {
    return runCatching { gson.fromJson(body, JsonObject::class.java) }.getOrNull()
        ?: throw SourceAdapterException(
            reason = SourceFailureReason.PARSE,
            message = "$errorLabel returned malformed JSON object",
        )
}

internal fun parseJsonElement(body: String, gson: Gson, errorLabel: String): JsonElement {
    return runCatching { gson.fromJson(body, JsonElement::class.java) }.getOrNull()
        ?: throw SourceAdapterException(
            reason = SourceFailureReason.PARSE,
            message = "$errorLabel returned malformed JSON",
        )
}

internal fun sourceNetworkFailure(siteLabel: String, error: IOException): Nothing {
    throw SourceAdapterException(
        reason = SourceFailureReason.NETWORK,
        message = "$siteLabel request failed",
        cause = error,
    )
}

internal fun classifyHttpFailure(
    statusCode: Int,
    body: String = "",
    authBlockedBody: (String) -> Boolean = { false },
): SourceFailureReason {
    return when {
        statusCode == 401 || statusCode == 403 -> SourceFailureReason.AUTH_REQUIRED
        statusCode == 429 -> SourceFailureReason.RATE_LIMITED
        statusCode in 500..599 -> SourceFailureReason.NETWORK
        authBlockedBody(body) -> SourceFailureReason.AUTH_REQUIRED
        else -> SourceFailureReason.UNKNOWN
    }
}

internal fun mimeFromUrlOrExt(url: String?, ext: String?): String? {
    return inferMimeFromUrl(url) ?: mimeFromFileExt(ext)
}

internal fun firstDurationMs(vararg candidates: Long?): Long? {
    return candidates.filterNotNull().firstOrNull { it > 0L }
}

internal fun JsonObject.durationFieldMs(name: String, multiplier: Long): Long? {
    val element = get(name)?.takeUnless { it.isJsonNull } ?: return null
    val parsed = when {
        element.isJsonPrimitive && element.asJsonPrimitive.isNumber -> {
            runCatching { (element.asDouble * multiplier).toLong() }.getOrNull()
        }
        element.isJsonPrimitive && element.asJsonPrimitive.isString -> {
            parseFlexibleDurationMs(element.asString, multiplier)
        }
        else -> null
    }
    return parsed?.takeIf { it > 0L }
}

internal fun JsonObject.ambiguousDurationFieldMs(name: String): Long? {
    val element = get(name)?.takeUnless { it.isJsonNull } ?: return null
    if (element.isJsonPrimitive && element.asJsonPrimitive.isNumber) {
        val numeric = runCatching { element.asDouble }.getOrNull() ?: return null
        val multiplier = if (numeric >= 1_000.0) 1L else 1_000L
        return (numeric * multiplier).toLong().takeIf { it > 0L }
    }
    return durationFieldMs(name = name, multiplier = 1_000L)
}

internal fun parseFlexibleDurationMs(raw: String, numericMultiplier: Long = 1_000L): Long? {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return null
    trimmed.toDoubleOrNull()?.let { return (it * numericMultiplier).toLong() }
    val parts = trimmed.split(':').mapNotNull { part -> part.trim().toLongOrNull() }
    if (parts.isEmpty() || parts.size != trimmed.count { it == ':' } + 1) return null
    val seconds = when (parts.size) {
        1 -> parts[0]
        2 -> parts[0] * 60L + parts[1]
        else -> parts.takeLast(3).let { (hours, minutes, seconds) ->
            hours * 3600L + minutes * 60L + seconds
        }
    }
    return seconds * 1_000L
}
