package com.theoriacodex.sources.common

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

internal inline fun parseEpochMs(value: String?, parse: (String) -> Instant): Long? {
    val text = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    return try {
        parse(text).toEpochMilli()
    } catch (_: DateTimeException) {
        null
    } catch (_: ArithmeticException) {
        null
    }
}

internal fun parseOffsetDateTimeMs(value: String?): Long? = parseEpochMs(value) { OffsetDateTime.parse(it).toInstant() }

private val BOORU_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm[:ss]")

internal fun parseBooruDateTimeMs(value: String?): Long? =
    parseEpochMs(value) { LocalDateTime.parse(it, BOORU_DATE_TIME).toInstant(ZoneOffset.UTC) }

private val GELBOORU_DATE_TIME = DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss Z yyyy", Locale.US)

internal fun parseGelbooruDateTimeMs(value: String?): Long? =
    parseEpochMs(value) { OffsetDateTime.parse(it, GELBOORU_DATE_TIME).toInstant() }
