package com.theoriacodex.app.sourceauth

import com.theoriacodex.domain.source.SiteCredentials
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Reads a pasted credentials snippet: a full URL or a bare `a=b&c=d` string, as account pages show
 * them. The user is `user_id` (Gelbooru family) or `login` (Danbooru family); the key is `api_key`.
 * Null unless both are present, so callers treat anything else as a plain typed value.
 */
fun parseCredentialSnippet(raw: String): SiteCredentials? {
    val query = raw.trim().let { if ('?' in it) it.substringAfter('?') else it }.substringBefore('#')
    val values = query.split('&').mapNotNull { segment ->
        val key = segment.substringBefore('=', "").trim().lowercase()
        if (key !in SNIPPET_KEYS) return@mapNotNull null
        val encoded = segment.substringAfter('=', "").trim()
        val value = runCatching { URLDecoder.decode(encoded, StandardCharsets.UTF_8) }.getOrDefault(encoded).trim()
        value.takeIf(String::isNotEmpty)?.let { key to it }
    }.toMap()
    val user = values["user_id"] ?: values["login"]
    val apiKey = values["api_key"]
    return if (user != null && apiKey != null) SiteCredentials(user, apiKey) else null
}

private val SNIPPET_KEYS = setOf("user_id", "login", "api_key")
