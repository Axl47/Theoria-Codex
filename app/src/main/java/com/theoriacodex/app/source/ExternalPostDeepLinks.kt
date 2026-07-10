package com.theoriacodex.app.source

import android.net.Uri
import com.theoriacodex.domain.model.HITOMI_ARTIST_IDENTITY_MAX_CODE_POINTS
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.model.canonicalHitomiArtistIdentity
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

data class ExternalPostDeepLink(
    val source: SourceKey,
    val sourceLabel: String,
    val postId: String,
)

data class ExternalCreatorDeepLink(
    val source: SourceKey,
    val sourceLabel: String,
    val creatorId: String,
    val profileUrl: String,
)

fun parseExternalPostDeepLink(uri: Uri): ExternalPostDeepLink? {
    return parseExternalPostDeepLink(uri.toString())
}

fun parseExternalPostDeepLink(rawUrl: String): ExternalPostDeepLink? {
    val uri = parseExternalUri(rawUrl) ?: return null
    parsePixivPostIdFromUri(uri)?.let { postId ->
        return ExternalPostDeepLink(
            source = SourceKey.PIXIV,
            sourceLabel = SourceKey.PIXIV.displayName(),
            postId = postId,
        )
    }
    parseNhentaiGalleryIdFromUri(uri)?.let { postId ->
        return ExternalPostDeepLink(
            source = SourceKey.NHENTAI,
            sourceLabel = SourceKey.NHENTAI.displayName(),
            postId = postId,
        )
    }
    parseHitomiGalleryIdFromUri(uri)?.let { postId ->
        return ExternalPostDeepLink(
            source = SourceKey.HITOMI,
            sourceLabel = SourceKey.HITOMI.displayName(),
            postId = postId,
        )
    }
    parseIwaraVideoIdFromUri(uri)?.let { postId ->
        return ExternalPostDeepLink(
            source = SourceKey.IWARA,
            sourceLabel = SourceKey.IWARA.displayName(),
            postId = postId,
        )
    }
    parseRule34XxxPostIdFromUri(uri)?.let { postId ->
        return ExternalPostDeepLink(
            source = SourceKey.RULE34XXX,
            sourceLabel = SourceKey.RULE34XXX.displayName(),
            postId = postId,
        )
    }
    parseRule34PahealPostIdFromUri(uri)?.let { postId ->
        return ExternalPostDeepLink(
            source = SourceKey.RULE34PAHEAL,
            sourceLabel = SourceKey.RULE34PAHEAL.displayName(),
            postId = postId,
        )
    }
    parseRule34VideoPostIdFromUri(uri)?.let { postId ->
        return ExternalPostDeepLink(
            source = SourceKey.RULE34VIDEO,
            sourceLabel = SourceKey.RULE34VIDEO.displayName(),
            postId = postId,
        )
    }
    parseRule34GenPostIdFromUri(uri)?.let { postId ->
        return ExternalPostDeepLink(
            source = SourceKey.RULE34GEN,
            sourceLabel = SourceKey.RULE34GEN.displayName(),
            postId = postId,
        )
    }
    parseGelbooruPostIdFromUri(uri)?.let { postId ->
        return ExternalPostDeepLink(
            source = SourceKey.GELBOORU,
            sourceLabel = SourceKey.GELBOORU.displayName(),
            postId = postId,
        )
    }
    return null
}

fun parseExternalCreatorDeepLink(uri: Uri): ExternalCreatorDeepLink? {
    return parseExternalCreatorDeepLink(uri.toString())
}

fun parseExternalCreatorDeepLink(rawUrl: String): ExternalCreatorDeepLink? {
    val uri = parseExternalUri(rawUrl) ?: return null
    parsePixivCreatorIdFromUri(uri)?.let { creatorId ->
        return ExternalCreatorDeepLink(
            source = SourceKey.PIXIV,
            sourceLabel = SourceKey.PIXIV.displayName(),
            creatorId = creatorId,
            profileUrl = "https://www.pixiv.net/en/users/$creatorId",
        )
    }
    parseGelbooruCreatorIdFromUri(uri)?.let { creatorId ->
        return ExternalCreatorDeepLink(
            source = SourceKey.GELBOORU,
            sourceLabel = SourceKey.GELBOORU.displayName(),
            creatorId = creatorId,
            profileUrl = "https://gelbooru.com/index.php?page=account&s=profile&id=$creatorId",
        )
    }
    parseHitomiArtistFromUri(uri)?.let { creatorId ->
        return ExternalCreatorDeepLink(
            source = SourceKey.HITOMI,
            sourceLabel = SourceKey.HITOMI.displayName(),
            creatorId = creatorId,
            profileUrl = "https://hitomi.la/artist/${encodePathSegment(creatorId)}-all.html",
        )
    }
    return null
}

private fun parsePixivPostIdFromUri(uri: ParsedExternalUri): String? {
    val scheme = uri.scheme
    val host = uri.host
    if (scheme != "https" && scheme != "http") return null
    if (host != "www.pixiv.com" && host != "pixiv.com" && host != "www.pixiv.net" && host != "pixiv.net") return null
    val path = uri.encodedPath
    val match = Regex("^/(?:([A-Za-z]{2})/)?artworks/(\\d+)(?:/)?$").matchEntire(path) ?: return null
    return match.groupValues.getOrNull(2)?.takeIf(String::isDigitsOnly)
}

private fun parsePixivCreatorIdFromUri(uri: ParsedExternalUri): String? {
    val scheme = uri.scheme
    val host = uri.host
    if (scheme != "https" && scheme != "http") return null
    if (host != "www.pixiv.com" && host != "pixiv.com" && host != "www.pixiv.net" && host != "pixiv.net") return null
    val path = uri.encodedPath
    val match = Regex("^/(?:([A-Za-z]{2})/)?users/(\\d+)(?:/[^?#]*)?/?$").matchEntire(path) ?: return null
    return match.groupValues.getOrNull(2)?.takeIf(String::isDigitsOnly)
}

private fun parseGelbooruPostIdFromUri(uri: ParsedExternalUri): String? {
    val scheme = uri.scheme
    val host = uri.host
    if (scheme != "https" && scheme != "http") return null
    if (host != "www.gelbooru.com" && host != "gelbooru.com") return null

    val path = uri.encodedPath.lowercase()
    if (path.isNotBlank() && path != "/" && path != "/index.php") return null

    val page = uri.queryParameters["page"]?.lowercase()
    val section = uri.queryParameters["s"]?.lowercase()
    val postId = uri.queryParameters["id"]
    if (page != "post" || section != "view") return null
    return postId?.takeIf(String::isDigitsOnly)
}

private fun parseGelbooruCreatorIdFromUri(uri: ParsedExternalUri): String? {
    val scheme = uri.scheme
    val host = uri.host
    if (scheme != "https" && scheme != "http") return null
    if (host != "www.gelbooru.com" && host != "gelbooru.com") return null

    val path = uri.encodedPath.lowercase()
    if (path.isNotBlank() && path != "/" && path != "/index.php") return null

    val page = uri.queryParameters["page"]?.lowercase()
    val section = uri.queryParameters["s"]?.lowercase()
    val creatorId = uri.queryParameters["id"]
    if (page != "account" || section != "profile") return null
    return creatorId?.takeIf(String::isDigitsOnly)
}

private fun parseRule34XxxPostIdFromUri(uri: ParsedExternalUri): String? {
    val scheme = uri.scheme
    val host = uri.host
    if (scheme != "https" && scheme != "http") return null
    if (host != "rule34.xxx" && host != "www.rule34.xxx") return null

    val path = uri.encodedPath.lowercase()
    if (path.isNotBlank() && path != "/" && path != "/index.php") return null

    val page = uri.queryParameters["page"]?.lowercase()
    val section = uri.queryParameters["s"]?.lowercase()
    val postId = uri.queryParameters["id"]
    if (page != "post" || section != "view") return null
    return postId?.takeIf(String::isDigitsOnly)
}

private fun parseIwaraVideoIdFromUri(uri: ParsedExternalUri): String? {
    val scheme = uri.scheme
    val host = uri.host
    if (scheme != "https" && scheme != "http") return null
    if (host != "www.iwara.tv" && host != "iwara.tv") return null

    val path = uri.encodedPath
    val match = Regex("^/video/([^/?#]+)(?:/[^/?#]+)?/?$").matchEntire(path) ?: return null
    return match.groupValues.getOrNull(1)?.takeIf(String::isNotBlank)
}

private fun parseRule34PahealPostIdFromUri(uri: ParsedExternalUri): String? {
    val scheme = uri.scheme
    val host = uri.host
    if (scheme != "https" && scheme != "http") return null
    if (host != "rule34.paheal.net") return null

    val path = uri.encodedPath
    val match = Regex("^/post/view/(\\d+)(?:/)?$").matchEntire(path) ?: return null
    return match.groupValues.getOrNull(1)?.takeIf(String::isDigitsOnly)
}

private fun parseRule34VideoPostIdFromUri(uri: ParsedExternalUri): String? {
    val scheme = uri.scheme
    val host = uri.host
    if (scheme != "https" && scheme != "http") return null
    if (host != "rule34video.com") return null

    val path = uri.encodedPath
    val match = Regex("^/video/(\\d+)(?:/[^/?#]+)?/?$").matchEntire(path) ?: return null
    return match.groupValues.getOrNull(1)?.takeIf(String::isDigitsOnly)
}

private fun parseRule34GenPostIdFromUri(uri: ParsedExternalUri): String? {
    val scheme = uri.scheme
    val host = uri.host
    if (scheme != "https" && scheme != "http") return null
    if (host != "rule34gen.com") return null

    val path = uri.encodedPath
    val match = Regex("^/video/(\\d+)(?:/[^/?#]+)?/?$").matchEntire(path) ?: return null
    return match.groupValues.getOrNull(1)?.takeIf(String::isDigitsOnly)
}

private fun parseNhentaiGalleryIdFromUri(uri: ParsedExternalUri): String? {
    val scheme = uri.scheme
    val host = uri.host
    if (scheme != "https" && scheme != "http") return null
    if (host != "nhentai.net" && host != "www.nhentai.net") return null

    val path = uri.encodedPath
    val match = Regex("^/g/(\\d+)(?:/\\d+)?/?$").matchEntire(path) ?: return null
    return match.groupValues.getOrNull(1)?.takeIf(String::isDigitsOnly)
}

private fun parseHitomiGalleryIdFromUri(uri: ParsedExternalUri): String? {
    if (!uri.isHttpUrlFor(HITOMI_HOSTS)) return null

    HITOMI_READER_PATH.matchEntire(uri.encodedPath)?.let { match ->
        return match.groupValues[1].takeIf(String::isDigitsOnly)
    }
    val match = HITOMI_GALLERY_PATH.matchEntire(uri.encodedPath) ?: return null
    return match.groupValues[1].takeIf(String::isDigitsOnly)
}

private fun parseHitomiArtistFromUri(uri: ParsedExternalUri): String? {
    if (!uri.isHttpUrlFor(HITOMI_HOSTS)) return null
    val encodedSlug = HITOMI_ARTIST_PATH.matchEntire(uri.encodedPath)
        ?.groupValues
        ?.getOrNull(1)
        ?: return null
    if (encodedSlug.length > MAX_HITOMI_ARTIST_ENCODED_LENGTH) return null
    val decoded = decodePathSegmentStrict(encodedSlug) ?: return null
    return canonicalHitomiArtistIdentity(decoded)
}

private fun parseExternalUri(rawUrl: String): ParsedExternalUri? {
    val parsed = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return null
    val scheme = parsed.scheme?.lowercase().orEmpty()
    val host = parsed.host?.lowercase().orEmpty()
    val path = parsed.rawPath.orEmpty()
    val queryParameters = parsed.rawQuery
        ?.split('&')
        ?.mapNotNull { entry ->
            if (entry.isBlank()) return@mapNotNull null
            val key = entry.substringBefore('=')
            if (key.isBlank()) return@mapNotNull null
            val value = entry.substringAfter('=', "")
            decodeQueryComponent(key) to decodeQueryComponent(value)
        }
        ?.toMap(linkedMapOf())
        .orEmpty()
    return ParsedExternalUri(
        scheme = scheme,
        host = host,
        encodedPath = path,
        queryParameters = queryParameters,
    )
}

private fun decodeQueryComponent(value: String): String {
    return URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}

private fun decodePathSegmentStrict(value: String): String? {
    val bytes = ByteArrayOutputStream(value.length)
    var index = 0
    while (index < value.length) {
        val character = value[index]
        if (character == '%') {
            if (index + 2 >= value.length) return null
            val high = value[index + 1].digitToIntOrNull(radix = 16) ?: return null
            val low = value[index + 2].digitToIntOrNull(radix = 16) ?: return null
            bytes.write((high shl 4) or low)
            index += 3
        } else {
            val codePoint = value.codePointAt(index)
            if (codePoint in 0xD800..0xDFFF) return null
            val encoded = String(Character.toChars(codePoint)).toByteArray(StandardCharsets.UTF_8)
            bytes.write(encoded)
            index += Character.charCount(codePoint)
        }
    }
    return runCatching {
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes.toByteArray()))
            .toString()
    }.getOrNull()
}

private fun encodePathSegment(value: String): String {
    return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
}

private fun ParsedExternalUri.isHttpUrlFor(hosts: Set<String>): Boolean {
    return (scheme == "https" || scheme == "http") && host in hosts
}

private data class ParsedExternalUri(
    val scheme: String,
    val host: String,
    val encodedPath: String,
    val queryParameters: Map<String, String>,
)

private fun String.isDigitsOnly(): Boolean {
    return isNotBlank() && all { it.isDigit() }
}

private const val MAX_HITOMI_ARTIST_ENCODED_LENGTH = HITOMI_ARTIST_IDENTITY_MAX_CODE_POINTS * 12

private val HITOMI_HOSTS = setOf("hitomi.la", "www.hitomi.la")
private val HITOMI_READER_PATH = Regex("^/reader/(\\d+)\\.html/?$")
private val HITOMI_GALLERY_PATH = Regex(
    "^/(?:anime|cg|doujinshi|manga|artistcg|gamecg|imageset)/[^/]+-(\\d+)\\.html/?$",
)
private val HITOMI_ARTIST_PATH = Regex("^/artist/([^/]+)-all\\.html/?$")
