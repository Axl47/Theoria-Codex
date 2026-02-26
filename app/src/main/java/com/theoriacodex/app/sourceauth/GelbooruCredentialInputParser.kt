package com.theoriacodex.app.sourceauth

import com.theoriacodex.sources.credentials.GelbooruCredentials
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

fun parseGelbooruCredentialInput(raw: String): GelbooruCredentials? {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return null

    val values = linkedMapOf<String, String>()
    extractQueryPairs(trimmed, values)

    val apiKey = values["api_key"].orEmpty().trim()
    val userId = values["user_id"].orEmpty().trim()
    if (apiKey.isBlank() || userId.isBlank()) return null

    return GelbooruCredentials(
        userId = userId,
        apiKey = apiKey,
    )
}

private fun extractQueryPairs(
    candidate: String,
    out: MutableMap<String, String>,
) {
    val query = when {
        '?' in candidate -> candidate.substringAfter('?', "")
        else -> candidate
    }.substringBefore('#')

    query.split('&').forEach { segment ->
        val part = segment.trim()
        if (part.isBlank()) return@forEach
        val key = part.substringBefore("=", "").trim().lowercase()
        if (key != "api_key" && key != "user_id") return@forEach
        val valueRaw = part.substringAfter("=", "").trim()
        if (valueRaw.isBlank()) return@forEach
        val value = runCatching {
            URLDecoder.decode(valueRaw, StandardCharsets.UTF_8.name())
        }.getOrDefault(valueRaw).trim()
        if (value.isNotBlank()) {
            out[key] = value
        }
    }
}
