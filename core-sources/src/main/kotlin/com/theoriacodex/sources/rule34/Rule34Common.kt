package com.theoriacodex.sources.rule34

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SortMode
import java.io.IOException
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

internal val RULE34_BROWSER_HEADERS = mapOf(
    "User-Agent" to "Mozilla/5.0",
)

internal fun classifyRule34HttpFailure(statusCode: Int): SourceFailureReason {
    return when (statusCode) {
        401, 403 -> SourceFailureReason.AUTH_REQUIRED
        404 -> SourceFailureReason.UNKNOWN
        429 -> SourceFailureReason.RATE_LIMITED
        in 500..599 -> SourceFailureReason.NETWORK
        else -> SourceFailureReason.UNKNOWN
    }
}

internal fun rule34NetworkFailure(siteLabel: String, error: IOException): Nothing {
    throw SourceAdapterException(
        reason = SourceFailureReason.NETWORK,
        message = "$siteLabel request failed",
        cause = error,
    )
}

internal fun parseJsonObject(body: String, gson: Gson, errorLabel: String): JsonObject {
    return runCatching { gson.fromJson(body, JsonObject::class.java) }.getOrNull()
        ?: throw SourceAdapterException(
            reason = SourceFailureReason.PARSE,
            message = "$errorLabel returned malformed JSON object",
        )
}

internal fun parseJsonArray(body: String, gson: Gson, errorLabel: String): JsonArray {
    return runCatching { gson.fromJson(body, JsonArray::class.java) }.getOrNull()
        ?: throw SourceAdapterException(
            reason = SourceFailureReason.PARSE,
            message = "$errorLabel returned malformed JSON array",
        )
}

internal fun jsonArrayOfObjects(body: String, gson: Gson, errorLabel: String): List<JsonObject> {
    val element = runCatching { gson.fromJson(body, JsonElement::class.java) }.getOrNull()
        ?: throw SourceAdapterException(
            reason = SourceFailureReason.PARSE,
            message = "$errorLabel returned malformed JSON",
        )

    return when {
        element.isJsonArray -> element.asJsonArray.mapNotNull { item ->
            item.takeIf(JsonElement::isJsonObject)?.asJsonObject
        }

        element.isJsonObject -> {
            val obj = element.asJsonObject
            sequenceOf("post", "tag")
                .mapNotNull { key ->
                    when {
                        obj.get(key)?.isJsonArray == true -> obj.getAsJsonArray(key).mapNotNull { item ->
                            item.takeIf(JsonElement::isJsonObject)?.asJsonObject
                        }

                        obj.get(key)?.isJsonObject == true -> listOf(obj.getAsJsonObject(key))
                        else -> null
                    }
                }
                .firstOrNull()
                .orEmpty()
        }

        else -> emptyList()
    }
}

internal fun compileRule34BooruTags(query: Query): String {
    val includeTags = query.includeTags
        .map(::normalizeRule34BooruTag)
        .filter(String::isNotBlank)
    val excludeTags = query.excludeTags
        .map(::normalizeRule34BooruTag)
        .filter(String::isNotBlank)
        .map { tag -> "-$tag" }

    val metatags = mutableListOf<String>()
    if (query.minScore != null) {
        metatags += "score:>=${query.minScore}"
    }
    if (query.sort == SortMode.TOP || query.sort == SortMode.POPULAR) {
        metatags += "sort:score"
    }

    return (includeTags + excludeTags + metatags)
        .take(40)
        .joinToString(" ")
}

internal fun normalizeRule34BooruTag(raw: String): String {
    return raw.trim()
        .replace(RULE34_WHITESPACE_REGEX, "_")
        .lowercase()
}

internal fun compileRule34VideoSearchText(query: Query): String {
    val includeTags = query.includeTags
        .map(String::trim)
        .filter(String::isNotBlank)
    return includeTags.joinToString(" ").trim()
}

internal fun parseTagSuggestionsFromSelect2(
    body: String,
    gson: Gson,
    defaultType: String,
): List<TagSuggestion> {
    val root = parseJsonObject(body, gson, "Rule34 video tag lookup")
    val items = when {
        root.get("items")?.isJsonArray == true -> root.getAsJsonArray("items")
        else -> JsonArray()
    }
    return items.mapNotNull { item ->
        val obj = item.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@mapNotNull null
        val text = obj.get("title")?.asString?.trim().orEmpty()
        if (text.isBlank()) return@mapNotNull null
        TagSuggestion(
            text = text,
            type = defaultType,
            count = obj.get("total")?.asString?.toIntOrNull() ?: obj.get("total")?.asInt,
        )
    }
}

internal fun parseRule34XxxAutocompleteSuggestions(
    body: String,
    gson: Gson,
): List<TagSuggestion> {
    val items = parseJsonArray(body, gson, "rule34.xxx autocomplete")
    return items.mapNotNull { item ->
        val obj = item.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@mapNotNull null
        val text = obj.get("value")?.asString?.trim().orEmpty()
        if (text.isBlank()) return@mapNotNull null
        TagSuggestion(
            text = text,
            type = "tag",
            count = parseTrailingCount(obj.get("label")?.asString),
        )
    }
}

internal fun parseTrailingCount(label: String?): Int? {
    val match = RULE34_TRAILING_COUNT_REGEX.find(label.orEmpty()) ?: return null
    return match.groupValues.getOrNull(1)?.replace(",", "")?.toIntOrNull()
}

internal fun parseRfc1123EpochMs(raw: String?): Long? {
    val value = raw?.trim().orEmpty()
    if (value.isBlank()) return null
    return runCatching {
        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    }.getOrNull()
}

internal fun parsePahealThumbDateEpochMs(raw: String?): Long? {
    val value = raw?.trim().orEmpty()
    if (value.isBlank()) return null
    val formatter = DateTimeFormatter.ofPattern("MMMM d, uuuu; HH:mm", Locale.US)
    return runCatching {
        OffsetDateTime.of(
            java.time.LocalDateTime.parse(value, formatter),
            ZoneOffset.UTC,
        ).toInstant().toEpochMilli()
    }.getOrNull()
}

internal fun encodePathSegment(value: String): String {
    return java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}

internal fun parseVideoSitePubDateEpochMs(raw: String?): Long? {
    val value = raw?.trim().orEmpty()
    if (value.isBlank()) return null
    val formatter = DateTimeFormatter.ofPattern("EEE dd MMM uuuu HH:mm:ss Z", Locale.US)
    return try {
        ZonedDateTime.parse(value, formatter).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        parseRfc1123EpochMs(value)
    }
}

private val RULE34_WHITESPACE_REGEX = Regex("\\s+")
private val RULE34_TRAILING_COUNT_REGEX = Regex("\\(([-\\d,]+)\\)\\s*$")
