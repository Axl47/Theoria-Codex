package com.theoriacodex.sources.rule34

import com.theoriacodex.sources.common.parseEpochMs
import com.theoriacodex.sources.http.BROWSER_USER_AGENT
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal val RULE34_BROWSER_HEADERS = mapOf(
    "User-Agent" to BROWSER_USER_AGENT,
)

internal fun parseRfc1123EpochMs(raw: String?): Long? =
    parseEpochMs(raw) { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }

