package com.theoriacodex.sources.common

import com.google.gson.Gson
import com.theoriacodex.sources.http.HostOutageKind
import com.theoriacodex.sources.http.HostUnavailableException
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.diagnostics.OffMainWork
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.sources.media.inferMimeFromUrl
import com.theoriacodex.sources.media.mimeFromFileExt
import com.theoriacodex.sources.http.SourceHttpResponse
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser

internal suspend fun <T> sourceCall(
    context: CoroutineContext = Dispatchers.Default,
    block: suspend () -> T,
): T = withContext(context) { block() }

internal fun originUrlOrNull(source: String?, pixivId: Long? = null): String? {
    pixivId?.takeIf { it > 0 }?.let { return PIXIV_ARTWORK_URL + it }
    val url = source?.trim()?.split(BOORU_WHITESPACE_REGEX)?.firstOrNull()
        ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        ?: return null
    return PXIMG_ARTWORK_ID_REGEX.find(url)?.let { PIXIV_ARTWORK_URL + it.groupValues[1] } ?: url
}

private const val PIXIV_ARTWORK_URL = "https://www.pixiv.net/artworks/"
private val PXIMG_ARTWORK_ID_REGEX = Regex("""^https?://i\d*\.pximg\.net/.*/(\d+)_(?:p\d+|ugoira)""")

internal fun parseJsonArray(body: String, gson: Gson, errorLabel: String): JsonArray {
    OffMainWork.note(OffMainWork.SOURCE_PARSE)
    return runCatching { gson.fromJson(body, JsonArray::class.java) }.getOrNull()
        ?: throw SourceAdapterException(
            reason = SourceFailureReason.PARSE,
            message = "$errorLabel returned malformed JSON array",
        )
}

internal fun parseJsonObject(body: String, gson: Gson, errorLabel: String): JsonObject {
    OffMainWork.note(OffMainWork.SOURCE_PARSE)
    return runCatching { gson.fromJson(body, JsonObject::class.java) }.getOrNull()
        ?: throw SourceAdapterException(
            reason = SourceFailureReason.PARSE,
            message = "$errorLabel returned malformed JSON object",
        )
}

internal fun parseJsonElement(body: String, gson: Gson, errorLabel: String): JsonElement {
    OffMainWork.note(OffMainWork.SOURCE_PARSE)
    return runCatching { gson.fromJson(body, JsonElement::class.java) }.getOrNull()
        ?: throw SourceAdapterException(
            reason = SourceFailureReason.PARSE,
            message = "$errorLabel returned malformed JSON",
        )
}

internal fun parseHtml(html: String, baseUri: String = ""): Document {
    OffMainWork.note(OffMainWork.SOURCE_PARSE)
    return Jsoup.parse(html, baseUri)
}

internal fun parseXml(xml: String, baseUri: String = ""): Document {
    OffMainWork.note(OffMainWork.SOURCE_PARSE)
    return Jsoup.parse(xml, baseUri, Parser.xmlParser())
}

internal fun parseHtmlFragment(html: String): Document {
    OffMainWork.note(OffMainWork.SOURCE_PARSE)
    return Jsoup.parseBodyFragment(html)
}

internal fun sourceNetworkFailure(siteLabel: String, error: IOException): Nothing =
    networkFailure("$siteLabel request failed", error)

internal fun networkFailure(message: String, error: IOException): Nothing {
    val unavailable = error as? HostUnavailableException
    throw SourceAdapterException(
        reason = if (unavailable?.kind == HostOutageKind.RATE_LIMITED) SourceFailureReason.RATE_LIMITED else SourceFailureReason.NETWORK,
        message = unavailable?.message ?: message,
        cause = error,
    )
}

internal fun classifyHttpFailure(
    statusCode: Int,
    body: String = "",
    badRequestReason: SourceFailureReason = SourceFailureReason.UNKNOWN,
    unauthorizedReason: SourceFailureReason = SourceFailureReason.AUTH_REQUIRED,
    forbiddenReason: SourceFailureReason = SourceFailureReason.AUTH_REQUIRED,
    authBlockedBody: (String) -> Boolean = { false },
): SourceFailureReason {
    return when {
        statusCode == 400 -> badRequestReason
        statusCode == 401 -> unauthorizedReason
        statusCode == 403 -> forbiddenReason
        statusCode == 429 -> SourceFailureReason.RATE_LIMITED
        statusCode in 500..599 -> SourceFailureReason.NETWORK
        authBlockedBody(body) -> SourceFailureReason.AUTH_REQUIRED
        else -> SourceFailureReason.UNKNOWN
    }
}

internal fun SourceHttpResponse.isSuccessful(): Boolean = statusCode in 200..299

internal fun SourceHttpResponse.matchesChallenge(
    statusCodes: Set<Int> = setOf(403),
    headerMarkers: Map<String, Set<String>> = mapOf(
        "cf-mitigated" to setOf("challenge"),
    ),
    bodyMarkers: Set<String> = setOf("cloudflare", "cf-mitigated"),
): Boolean {
    if (statusCode !in statusCodes) return false

    val hasChallengeHeader = headers.any { (name, values) ->
        val expectedMarkers = headerMarkers.entries
            .firstOrNull { (headerName, _) -> headerName.equals(name, ignoreCase = true) }
            ?.value
            .orEmpty()
        expectedMarkers.isNotEmpty() && values.any { value ->
            expectedMarkers.any { marker -> value.contains(marker, ignoreCase = true) }
        }
    }
    if (hasChallengeHeader) return true

    return bodyMarkers.any { marker -> body.contains(marker, ignoreCase = true) }
}

internal fun JsonElement?.asStringOrNull(): String? {
    if (this == null || isJsonNull || !isJsonPrimitive) return null
    val primitive = asJsonPrimitive
    if (!primitive.isString && !primitive.isNumber && !primitive.isBoolean) return null
    return runCatching { primitive.asString }.getOrNull()
}

internal fun JsonElement?.asIntOrNull(): Int? {
    if (this == null || isJsonNull || !isJsonPrimitive) return null
    val primitive = asJsonPrimitive
    return runCatching {
        when {
            primitive.isNumber -> primitive.asInt
            primitive.isString -> primitive.asString.trim().toInt()
            else -> null
        }
    }.getOrNull()
}

internal fun JsonElement?.asLongOrNull(): Long? {
    if (this == null || isJsonNull || !isJsonPrimitive) return null
    val primitive = asJsonPrimitive
    return runCatching {
        when {
            primitive.isNumber -> primitive.asLong
            primitive.isString -> primitive.asString.trim().toLong()
            else -> null
        }
    }.getOrNull()
}

internal fun JsonElement?.asBooleanOrNull(): Boolean? {
    if (this == null || isJsonNull || !isJsonPrimitive) return null
    val primitive = asJsonPrimitive
    if (primitive.isBoolean) return runCatching { primitive.asBoolean }.getOrNull()
    if (!primitive.isString) return null
    return when (primitive.asString.trim().lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }
}

internal fun JsonObject.stringValue(name: String): String? = get(name).asStringOrNull()

internal fun JsonObject.intValue(name: String): Int? = get(name).asIntOrNull()

internal fun JsonObject.longValue(name: String): Long? = get(name).asLongOrNull()

internal fun JsonObject.booleanValue(name: String): Boolean? = get(name).asBooleanOrNull()

internal fun JsonObject.optionalJsonArray(name: String): JsonArray? {
    return get(name)?.takeIf(JsonElement::isJsonArray)?.asJsonArray
}

internal fun JsonObject.optionalJsonObject(name: String): JsonObject? {
    return get(name)?.takeIf(JsonElement::isJsonObject)?.asJsonObject
}

internal fun JsonArray?.elementsOrEmpty(): List<JsonElement> = this?.toList().orEmpty()

internal fun JsonElement.objectList(vararg envelopeKeys: String): List<JsonObject> {
    if (isJsonArray) {
        return asJsonArray.mapNotNull { item ->
            item.takeIf(JsonElement::isJsonObject)?.asJsonObject
        }
    }
    if (!isJsonObject) return emptyList()

    val root = asJsonObject
    return envelopeKeys.asSequence()
        .mapNotNull { key ->
            when {
                root.get(key)?.isJsonArray == true -> root.getAsJsonArray(key).mapNotNull { item ->
                    item.takeIf(JsonElement::isJsonObject)?.asJsonObject
                }
                root.get(key)?.isJsonObject == true -> listOf(root.getAsJsonObject(key))
                else -> null
            }
        }
        .firstOrNull()
        .orEmpty()
}

internal fun normalizeBooruTag(raw: String): String = raw.trim().replace(BOORU_WHITESPACE_REGEX, "_").lowercase()

private val BOORU_WHITESPACE_REGEX = Regex("\\s+")

internal fun mimeFromUrlOrExt(url: String?, ext: String?): String? {
    return inferMimeFromUrl(url) ?: mimeFromFileExt(ext)
}

internal fun booruPreview(url: String?, fullMime: String?): ImageRef =
    ImageRef(url = url, localPath = null, mime = mimeFromUrlOrExt(url, null) ?: fullMime)

internal fun booruFull(url: String, mime: String?, sampleUrl: String?): ImageRef =
    ImageRef(url = url, localPath = null, mime = mime, progressiveUrls = listOfNotNull(sampleUrl).filter { it != url })

internal fun firstDurationMs(vararg candidates: Long?): Long? {
    return candidates.filterNotNull().firstOrNull { it > 0L }
}

internal fun JsonObject.durationFieldMs(name: String, multiplier: Long): Long? {
    val element = get(name)?.takeUnless { it.isJsonNull } ?: return null
    val parsed = when {
        element.isJsonPrimitive && element.asJsonPrimitive.isNumber -> {
            runCatching { element.asDouble }.getOrNull()
                ?.let { value -> scaleDurationMs(value, multiplier) }
        }
        element.isJsonPrimitive && element.asJsonPrimitive.isString -> {
            parseFlexibleDurationMs(element.asString, multiplier)
        }
        else -> null
    }
    return parsed?.takeIf { it > 0L }
}

internal fun JsonObject.ambiguousDurationFieldMs(name: String): Long? {
    val element = get(name)?.takeUnless { it.isJsonNull } ?: return null
    if (element.isJsonPrimitive && element.asJsonPrimitive.isNumber) {
        val numeric = runCatching { element.asDouble }.getOrNull() ?: return null
        val multiplier = if (numeric >= 1_000.0) 1L else 1_000L
        return scaleDurationMs(numeric, multiplier)
    }
    return durationFieldMs(name = name, multiplier = 1_000L)
}

internal fun parseFlexibleDurationMs(raw: String, numericMultiplier: Long = 1_000L): Long? {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return null
    trimmed.toDoubleOrNull()?.let { return scaleDurationMs(it, numericMultiplier) }
    val parts = trimmed.split(':').mapNotNull { part -> part.trim().toLongOrNull() }
    if (parts.isEmpty() || parts.size != trimmed.count { it == ':' } + 1) return null
    val seconds = runCatching {
        when (parts.size) {
            1 -> parts[0]
            2 -> Math.addExact(Math.multiplyExact(parts[0], 60L), parts[1])
            else -> parts.takeLast(3).let { (hours, minutes, seconds) ->
                Math.addExact(
                    Math.addExact(Math.multiplyExact(hours, 3_600L), Math.multiplyExact(minutes, 60L)),
                    seconds,
                )
            }
        }
    }.getOrNull() ?: return null
    return runCatching { Math.multiplyExact(seconds, 1_000L) }
        .getOrNull()
        ?.takeIf { it > 0L }
}

private fun scaleDurationMs(value: Double, multiplier: Long): Long? {
    if (!value.isFinite() || value <= 0.0 || multiplier <= 0L) return null
    val scaled = value * multiplier.toDouble()
    if (!scaled.isFinite() || scaled <= 0.0 || scaled >= Long.MAX_VALUE.toDouble()) return null
    return scaled.toLong().takeIf { it > 0L }
}

internal fun httpsMediaUrl(rawUrl: String?): String? {
    val url = rawUrl?.trim()?.takeIf(String::isNotBlank) ?: return null
    return when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("http://", ignoreCase = true) -> "https://" + url.substring("http://".length)
        else -> url
    }
}
