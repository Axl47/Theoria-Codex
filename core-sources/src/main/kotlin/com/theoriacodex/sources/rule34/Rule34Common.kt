package com.theoriacodex.sources.rule34

import com.theoriacodex.sources.http.BROWSER_USER_AGENT
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal val RULE34_BROWSER_HEADERS = mapOf(
    "User-Agent" to BROWSER_USER_AGENT,
)

internal fun parseRfc1123EpochMs(raw: String?): Long? {
    val value = raw?.trim().orEmpty()
    if (value.isBlank()) return null
    return runCatching {
        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    }.getOrNull()
}

internal fun encodePathSegment(value: String): String {
    return java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}

