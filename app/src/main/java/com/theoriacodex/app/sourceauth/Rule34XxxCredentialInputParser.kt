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
        val part = segment.trim()
        if (part.isBlank()) return@forEach
        val key = part.substringBefore("=", "").trim().lowercase()
        if (key != "api_key" && key != "user_id") return@forEach
        val rawValue = part.substringAfter("=", "").trim()
        if (rawValue.isBlank()) return@forEach
        val value = runCatching {
            URLDecoder.decode(rawValue, StandardCharsets.UTF_8.name())
        }.getOrDefault(rawValue).trim()
        if (value.isNotBlank()) {
            values[key] = value
        }
    }

    val userId = values["user_id"].orEmpty().trim()
    val apiKey = values["api_key"].orEmpty().trim()
    if (userId.isBlank() || apiKey.isBlank()) return null

    return Rule34XxxCredentials(
        userId = userId,
        apiKey = apiKey,
    )
}
