package com.theoriacodex.app.source

import android.net.Uri
import com.theoriacodex.domain.model.SourceKey
import java.net.URI
import java.net.URLDecoder
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
    val match = Regex("^/g/(\\d+)(?:/)?$").matchEntire(path) ?: return null
    return match.groupValues.getOrNull(1)?.takeIf(String::isDigitsOnly)
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
    return URLDecoder.decode(value, StandardCharsets.UTF_8)
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
