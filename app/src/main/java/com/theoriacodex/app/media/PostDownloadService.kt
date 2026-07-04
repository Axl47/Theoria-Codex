package com.theoriacodex.app.media

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.theoriacodex.app.source.requestHeaders
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post

object PostDownloadService {
    fun enqueuePostDownload(context: Context, post: Post): Boolean {
        val candidate = postDownloadMediaCandidate(post) ?: return false
        val fileName = buildDownloadFileName(
            post = post,
            media = candidate.ref,
            fallbackUrl = candidate.url,
            pageIndex = null,
            totalPages = 1,
        )
        return enqueueDownload(
            context = context,
            url = candidate.url,
            mime = candidate.ref.mime,
            headers = candidate.requestHeaders,
            fileName = fileName,
            description = post.pageUrl ?: DOWNLOAD_DESCRIPTION,
        )
    }

    fun enqueueViewerDownload(
        context: Context,
        post: Post,
        media: ImageRef,
        pageIndex: Int,
        totalPages: Int,
    ): Boolean {
        val url = media.url?.takeIf(String::isNotBlank) ?: return false
        val fileName = buildDownloadFileName(
            post = post,
            media = media,
            fallbackUrl = url,
            pageIndex = pageIndex,
            totalPages = totalPages,
        )
        return enqueueDownload(
            context = context,
            url = url,
            mime = media.mime,
            headers = post.id.source.requestHeaders(),
            fileName = fileName,
            description = post.pageUrl ?: DOWNLOAD_DESCRIPTION,
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
    ): Boolean {
        val request = DownloadManager.Request(Uri.parse(url))
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
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

        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return false
        return runCatching {
            manager.enqueue(request)
            true
        }.getOrElse { false }
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
