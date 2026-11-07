package com.theoriacodex.sources.custom

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.theoriacodex.domain.source.ProbeFailure
import com.theoriacodex.sources.gelbooru.looksLikeGelbooruAuthFailure
import com.theoriacodex.sources.http.SourceHttpResponse
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

// Evidence fingerprints, each pinned by a captured fixture under
// core-sources/src/test/resources/custom-sites/ (see its README for the per-site shapes).

/** Danbooru `/counts/posts.json`: `{"counts":{"posts":N}}`; N is null when the count timed out. */
internal fun SourceHttpResponse.isDanbooruCounts(): Boolean =
    statusCode == HTTP_OK && jsonBody()?.takeIf(JsonElement::isJsonObject)?.asJsonObject
        ?.get("counts")?.takeIf(JsonElement::isJsonObject)?.asJsonObject?.has("posts") == true

/**
 * Danbooru refusing access: 401/403 with its error envelope
 * `{"success":false,"error":"SessionLoader::AuthenticationFailure","message":"Invalid API key"}`.
 */
internal fun SourceHttpResponse.isDanbooruAuthRefusal(): Boolean {
    if (statusCode != HTTP_UNAUTHORIZED && statusCode != HTTP_FORBIDDEN) return false
    val envelope = jsonBody()?.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return false
    val success = envelope.get("success")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
    return success?.asBoolean == false && envelope.get("error")?.isJsonPrimitive == true
}

/** Anonymous or per-user `tag_query_limit` from Danbooru's `/profile.json`. */
internal fun SourceHttpResponse.danbooruTagQueryLimit(): Int? =
    takeIf { statusCode == HTTP_OK }?.jsonBody()?.takeIf(JsonElement::isJsonObject)?.asJsonObject
        ?.get("tag_query_limit")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
        ?.asInt?.takeIf { it > 0 }

/** Gelbooru 0.2 DAPI without `json=1`: a `<posts count="N" …>` envelope, present even when empty. */
internal fun SourceHttpResponse.isGelbooruPostsEnvelope(): Boolean =
    statusCode == HTTP_OK && GELBOORU_POSTS_ENVELOPE.containsMatchIn(body.take(ENVELOPE_SCAN_CHARS))

/**
 * Gelbooru 0.2 refusing keyless access. Gelbooru answers 401 with an empty body; rule34.xxx answers
 * 200 with `<error>Missing authentication…</error>` or a JSON string. An empty 401 that carries a
 * `WWW-Authenticate` challenge is HTTP authentication in front of some other site, and HTML pages
 * (including "Access denied" interstitials) are never auth evidence.
 */
internal fun SourceHttpResponse.isGelbooruAuthRefusal(): Boolean = when {
    statusCode == HTTP_UNAUTHORIZED && body.isBlank() -> header("WWW-Authenticate") == null
    statusCode !in GELBOORU_AUTH_STATUSES || looksLikeHtml() -> false
    else -> looksLikeGelbooruAuthFailure(body)
}

/** Moebooru `/post.json`: an array of posts carrying `md5` and `file_url`. */
internal fun SourceHttpResponse.isMoebooruPostList(): Boolean {
    if (statusCode != HTTP_OK) return false
    val first = jsonBody()?.takeIf(JsonElement::isJsonArray)?.asJsonArray?.firstOrNull() ?: return false
    return first.isJsonObject && first.asJsonObject.has("md5") && first.asJsonObject.has("file_url")
}

/**
 * Classifies a response no family claimed. Challenge and maintenance pages (403/503 HTML) and
 * server errors are inconclusive; anything else is a well-formed answer that matched nothing.
 */
internal fun SourceHttpResponse.unclaimed(): FamilyProbe = when {
    (statusCode == HTTP_FORBIDDEN || statusCode == HTTP_UNAVAILABLE) && looksLikeHtml() ->
        FamilyProbe.Failed(ProbeFailure.BLOCKED)
    statusCode >= HTTP_SERVER_ERROR || statusCode == HTTP_TOO_MANY_REQUESTS -> FamilyProbe.Failed(ProbeFailure.HTTP_ERROR)
    else -> FamilyProbe.NoMatch
}

/** How a probe reports a transport failure. */
internal fun probeFailureOf(error: IOException): ProbeFailure = when (error) {
    is CustomSiteRedirectException -> error.failure
    is UnknownHostException -> ProbeFailure.DNS
    is SSLException -> ProbeFailure.TLS
    is SocketTimeoutException -> ProbeFailure.TIMEOUT
    else -> ProbeFailure.HTTP_ERROR
}

private fun SourceHttpResponse.jsonBody(): JsonElement? =
    body.takeIf(String::isNotBlank)?.let { runCatching { JsonParser.parseString(it) }.getOrNull() }

private fun SourceHttpResponse.looksLikeHtml(): Boolean {
    val start = body.trimStart().take(HTML_SNIFF_CHARS).lowercase()
    return start.startsWith("<!doctype html") || start.startsWith("<html")
}

private fun SourceHttpResponse.header(name: String): String? =
    headers.entries.firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

private const val HTTP_OK = 200
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_SERVER_ERROR = 500
private const val HTTP_UNAVAILABLE = 503
private const val ENVELOPE_SCAN_CHARS = 512
private const val HTML_SNIFF_CHARS = 32
private val GELBOORU_AUTH_STATUSES = setOf(HTTP_OK, HTTP_UNAUTHORIZED, HTTP_FORBIDDEN)
private val GELBOORU_POSTS_ENVELOPE = Regex("""^\s*(<\?xml[^>]*\?>\s*)?<posts\b[^>]*\bcount="\d+"""")
