package com.theoriacodex.app.media

import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import androidx.core.net.toUri
import com.theoriacodex.app.source.requestHeaders
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import java.net.URLEncoder
import java.security.MessageDigest

object PostDownloadService {
    internal fun enqueuePostDownloadId(
        context: Context,
        post: Post,
        settings: com.theoriacodex.data.repository.CacheSettings,
        marker: String? = null,
    ): Long? {
        val candidate = postDownloadMediaCandidate(post) ?: return null
        val media = candidate.ref.withVideoQuality(settings.downloadQuality, context.isMediaNetworkMetered())
        val fileName = buildDownloadFileName(
            post = post,
            media = media,
            fallbackUrl = media.url ?: candidate.url,
            pageIndex = null,
            totalPages = 1,
        )
        return enqueueDownload(
            context = context,
            url = media.url ?: candidate.url,
            mime = media.mime,
            headers = candidate.requestHeaders,
            fileName = fileName,
            description = post.pageUrl ?: DOWNLOAD_DESCRIPTION,
            settings = settings,
            marker = marker,
        )
    }

    internal fun enqueueViewerDownloadId(
        context: Context,
        post: Post,
        media: ImageRef,
        pageIndex: Int,
        totalPages: Int,
        settings: com.theoriacodex.data.repository.CacheSettings,
        marker: String? = null,
    ): Long? {
        val selected = media.withVideoQuality(settings.downloadQuality, context.isMediaNetworkMetered())
        val url = selected.url?.takeIf(String::isNotBlank) ?: return null
        val fileName = buildDownloadFileName(
            post = post,
            media = selected,
            fallbackUrl = url,
            pageIndex = pageIndex,
            totalPages = totalPages,
        )
        return enqueueDownload(
            context = context,
            url = url,
            mime = selected.mime,
            headers = post.id.source.requestHeaders(),
            fileName = fileName,
            description = post.pageUrl ?: DOWNLOAD_DESCRIPTION,
            settings = settings,
            marker = marker,
        )
    }

    internal fun buildDownloadFileName(
        post: Post,
        media: ImageRef,
        fallbackUrl: String,
        pageIndex: Int?,
        totalPages: Int,
    ): String {
        val extension = fileExtension(fallbackUrl, media.mime)
        val title = post.title?.sanitizeFileName()?.takeUtf8Bytes(100)?.takeIf(String::isNotBlank)
        val encodedId = URLEncoder.encode(post.id.sourcePostId, Charsets.UTF_8.name())
        val id = encodedId.takeIf { it.length <= 96 } ?: MessageDigest.getInstance("SHA-256")
            .digest(post.id.sourcePostId.toByteArray()).take(16).joinToString("") { "%02x".format(it) }
        val identity = "${post.id.source.name.lowercase()}_$id"
        val base = title?.let { "${it}_$identity" } ?: identity
        val pageSuffix = if (pageIndex != null && totalPages > 1) "_p${pageIndex + 1}" else ""
        return if (extension.isNotBlank()) {
            "${base}$pageSuffix.$extension"
        } else {
            "$base$pageSuffix"
        }
    }

    private fun enqueueDownload(
        context: Context,
        url: String,
        mime: String?,
        headers: Map<String, String>,
        fileName: String,
        description: String,
        settings: com.theoriacodex.data.repository.CacheSettings,
        marker: String? = null,
    ): Long? {
        val tracked = if (marker == null) url.toUri() else url.toUri().buildUpon().fragment(downloadRequestMarker(marker)).build()
        val request = DownloadManager.Request(tracked)
            .setAllowedOverMetered(settings.downloadsOverMetered)
            .setAllowedOverRoaming(settings.downloadsOverRoaming)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        if (!mime.isNullOrBlank()) {
            request.setMimeType(mime)
        }
        headers.forEach { (name, value) ->
            request.addRequestHeader(name, value)
        }
        request.setTitle(fileName)
        request.setDescription(description)
        runCatching {
            request.setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                "$DOWNLOAD_DIRECTORY/$fileName",
            )
        }.onFailure {
            request.setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                fileName,
            )
        }

        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return null
        return runCatching {
            manager.enqueue(request)
        }.getOrNull()
    }
}

private const val DOWNLOAD_DIRECTORY = "TheoriaCodex"
private const val DOWNLOAD_DESCRIPTION = "Saved from Theoria Codex"

private fun fileExtension(location: String, mime: String?): String {
    val pathExtension = location
        .substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('/', "")
        .substringAfterLast('.', "")
        .takeIf { extension -> extension.matches(Regex("[A-Za-z0-9]{1,8}")) }
    if (!pathExtension.isNullOrBlank()) {
        return normalizeExtension(pathExtension)
    }
    return when (mime?.substringBefore(';')?.trim()?.lowercase()) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "video/mp4" -> "mp4"
        "video/webm" -> "webm"
        else -> ""
    }
}

private fun normalizeExtension(extension: String): String {
    return when (val normalized = extension.trim().lowercase()) {
        "jpeg" -> "jpg"
        else -> normalized
    }
}

private fun String.sanitizeFileName(): String {
    return trim().replace(Regex("[\\p{Cntrl}/\\\\:*?\"<>|\\s]+"), "_").trim('_', '.')
}

private fun String.takeUtf8Bytes(limit: Int): String = buildString {
    var bytes = 0
    var offset = 0
    while (offset < this@takeUtf8Bytes.length) {
        val point = this@takeUtf8Bytes.codePointAt(offset)
        val text = String(Character.toChars(point))
        bytes += text.toByteArray(Charsets.UTF_8).size
        if (bytes > limit) break
        append(text)
        offset += Character.charCount(point)
    }
}

/** Fragment metadata remains local to URI handling and never enters the HTTP request target. */
internal fun downloadRequestMarker(marker: String): String = "theoria-download:$marker"
