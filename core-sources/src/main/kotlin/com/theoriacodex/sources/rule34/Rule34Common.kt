package com.theoriacodex.sources.rule34

import com.google.gson.Gson
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.Query
import com.theoriacodex.sources.common.elementsOrEmpty
import com.theoriacodex.sources.common.intValue
import com.theoriacodex.sources.common.optionalJsonArray
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

