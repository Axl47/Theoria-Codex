package com.theoriacodex.app.sourceauth

import com.theoriacodex.sources.credentials.Rule34XxxCredentials
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

fun parseRule34XxxCredentialInput(raw: String): Rule34XxxCredentials? {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return null

    val values = linkedMapOf<String, String>()
    val query = when {
        '?' in trimmed -> trimmed.substringAfter('?', "")
        else -> trimmed
    }.substringBefore('#')

    query.split('&').forEach { segment ->
        parseCredentialParameter(segment)?.let { (key, value) -> values[key] = value }
    }

    val userId = values["user_id"].orEmpty().trim()
    val apiKey = values["api_key"].orEmpty().trim()
    if (userId.isBlank() || apiKey.isBlank()) return null

    return Rule34XxxCredentials(
        userId = userId,
        apiKey = apiKey,
    )
}

private fun parseCredentialParameter(segment: String): Pair<String, String>? {
    val part = segment.trim()
    if (part.isBlank()) return null
    val key = part.substringBefore("=", "").trim().lowercase()
    if (key != "api_key" && key != "user_id") return null
    val rawValue = part.substringAfter("=", "").trim()
    if (rawValue.isBlank()) return null
    val value = runCatching {
        URLDecoder.decode(rawValue, StandardCharsets.UTF_8.name())
    }.getOrDefault(rawValue).trim()
    return value.takeIf(String::isNotBlank)?.let { key to it }
}
