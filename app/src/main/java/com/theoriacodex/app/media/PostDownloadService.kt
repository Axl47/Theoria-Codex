package com.theoriacodex.app.media

import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import androidx.core.net.toUri
import com.theoriacodex.app.source.requestHeaders
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post

object PostDownloadService {
    fun enqueuePostDownload(context: Context, post: Post,
        settings: com.theoriacodex.data.repository.CacheSettings = com.theoriacodex.data.repository.CacheSettings(),
    ): Boolean = enqueuePostDownloadId(context, post, settings) != null

    internal fun enqueuePostDownloadId(
        context: Context,
        post: Post,
        settings: com.theoriacodex.data.repository.CacheSettings,
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
        )
    }

    fun enqueueViewerDownload(
        context: Context,
        post: Post,
        media: ImageRef,
        pageIndex: Int,
        totalPages: Int,
        settings: com.theoriacodex.data.repository.CacheSettings = com.theoriacodex.data.repository.CacheSettings(),
    ): Boolean = enqueueViewerDownloadId(context, post, media, pageIndex, totalPages, settings) != null

    internal fun enqueueViewerDownloadId(
        context: Context,
        post: Post,
        media: ImageRef,
        pageIndex: Int,
        totalPages: Int,
        settings: com.theoriacodex.data.repository.CacheSettings,
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
        val base = post.title
            ?.sanitizeFileName()
            ?.takeIf { it.isNotBlank() }
            ?: "${post.id.source.name.lowercase()}_${post.id.sourcePostId}"
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
    ): Long? {
        val request = DownloadManager.Request(url.toUri())
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
    val cleaned = trim().replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_')
    return cleaned.ifBlank { "image" }
}
