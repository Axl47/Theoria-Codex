package com.theoriacodex.sources.common

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode

/**
 * Reduces an HTML fragment to plain text: entities decoded, source whitespace collapsed as a browser
 * would, `<br>` turned into newlines and every other tag dropped. The result is trimmed.
 */
internal fun htmlFragmentToText(html: String): String {
    val body = Jsoup.parseBodyFragment(html).body()
    body.select("script, style").remove()
    body.allElements.forEach { element ->
        element.textNodes().forEach { node -> node.text(node.wholeText.replace(WHITESPACE_RUN, " ")) }
    }
    body.select("br").forEach { it.replaceWith(TextNode("\n")) }
    return body.wholeText().lines().joinToString("\n") { it.trim() }.trim()
}

private val WHITESPACE_RUN = Regex("\\s+")

/** Epoch milliseconds of an ISO-8601 timestamp that carries an offset, or null when it does not parse. */
internal fun parseOffsetDateTimeMs(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    return try {
        OffsetDateTime.parse(value.trim()).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }
}

private val BOORU_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm[:ss]")

/**
 * Epoch milliseconds of a Gelbooru-engine timestamp (`2026-01-02 03:04` or `…03:04:05`), which
 * carries no zone and is read as UTC. Null when it does not parse.
 */
internal fun parseBooruDateTimeMs(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    return try {
        LocalDateTime.parse(value.trim(), BOORU_DATE_TIME).toInstant(ZoneOffset.UTC).toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }
}
