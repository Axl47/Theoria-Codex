package com.theoriacodex.sources.rule34

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.sources.common.asStringOrNull
import com.theoriacodex.sources.common.elementsOrEmpty
import com.theoriacodex.sources.common.intValue
import com.theoriacodex.sources.common.objectList
import com.theoriacodex.sources.common.optionalJsonArray
import com.theoriacodex.sources.common.parseJsonArray
import com.theoriacodex.sources.common.parseJsonElement
import com.theoriacodex.sources.common.parseJsonObject
import com.theoriacodex.sources.common.stringValue
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

internal val RULE34_BROWSER_HEADERS = mapOf(
    "User-Agent" to "Mozilla/5.0",
)

internal fun jsonArrayOfObjects(body: String, gson: Gson, errorLabel: String): List<JsonObject> {
    return parseJsonElement(body, gson, errorLabel).objectList("post", "tag")
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
    return root.optionalJsonArray("items").elementsOrEmpty().mapNotNull { item ->
        val obj = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        val text = obj.stringValue("title")?.trim().orEmpty()
        if (text.isBlank()) return@mapNotNull null
        TagSuggestion(
            text = text,
            type = defaultType,
            count = obj.stringValue("total")?.toIntOrNull() ?: obj.intValue("total"),
        )
    }
}

internal fun parseRule34XxxAutocompleteSuggestions(
    body: String,
    gson: Gson,
): List<TagSuggestion> {
    val items = parseJsonArray(body, gson, "rule34.xxx autocomplete")
    return items.mapNotNull { item ->
        val obj = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        val text = obj.stringValue("value")?.trim().orEmpty()
        if (text.isBlank()) return@mapNotNull null
        TagSuggestion(
            text = text,
            type = "tag",
            count = parseTrailingCount(obj.get("label").asStringOrNull()),
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
