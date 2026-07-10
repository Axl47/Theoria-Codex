package com.theoriacodex.app.viewer

import android.content.Context
import android.webkit.URLUtil
import com.theoriacodex.domain.model.ImageRef
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal const val VIEWER_VIDEO_PREFETCH_MAX_BYTES = 16L * 1024L * 1024L

internal enum class ViewerVideoPrefetchAction {
    DOWNLOAD_BOUNDED,
    SKIP,
    UNAVAILABLE,
}

internal data class ViewerVideoPrefetchPlan(
    val action: ViewerVideoPrefetchAction,
    val expectedBytes: Long? = null,
)

internal enum class ViewerVideoCopyState {
    COMPLETE,
    LIMIT_EXCEEDED,
}

internal data class ViewerVideoCopyResult(
    val state: ViewerVideoCopyState,
    val bytesWritten: Long,
)

/**
 * Caches only responses proven to be complete and small. Large or partial range responses are
 * intentionally left to Media3, whose HTTP data source streams and seeks with source headers.
 */
internal suspend fun prefetchViewerVideoMediaBounded(
    context: Context,
    media: ImageRef,
    headers: Map<String, String>,
): Boolean = runNonFatalViewerVideoPrefetch {
    withContext(Dispatchers.IO) {
        val location = media.localPath ?: media.url ?: return@withContext false
        when {
            location.isHttpLocation() -> prefetchRemoteViewerVideo(
                context = context,
                remoteUrl = location,
                mime = media.mime,
                headers = headers,
            )

            location.startsWith("content://", ignoreCase = true) -> true
            else -> File(location).let { file -> file.exists() && file.length() > 0L }
        }
    }
}

internal suspend fun runNonFatalViewerVideoPrefetch(
    block: suspend () -> Boolean,
): Boolean {
    return try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }
}

internal fun planViewerVideoPrefetch(
    statusCode: Int,
    contentLength: Long?,
    contentRange: String?,
    maxBytes: Long = VIEWER_VIDEO_PREFETCH_MAX_BYTES,
): ViewerVideoPrefetchPlan {
    if (maxBytes <= 0L) return ViewerVideoPrefetchPlan(ViewerVideoPrefetchAction.UNAVAILABLE)
    val normalizedLength = contentLength?.takeIf { length -> length >= 0L }
    return when (statusCode) {
        HttpURLConnection.HTTP_OK -> when {
            normalizedLength == 0L -> ViewerVideoPrefetchPlan(ViewerVideoPrefetchAction.UNAVAILABLE)
            normalizedLength != null && normalizedLength > maxBytes -> {
                ViewerVideoPrefetchPlan(ViewerVideoPrefetchAction.SKIP)
            }

            else -> ViewerVideoPrefetchPlan(
                action = ViewerVideoPrefetchAction.DOWNLOAD_BOUNDED,
                expectedBytes = normalizedLength,
            )
        }

        HttpURLConnection.HTTP_PARTIAL -> {
            val range = parseViewerVideoContentRange(contentRange)
                ?: return ViewerVideoPrefetchPlan(ViewerVideoPrefetchAction.SKIP)
            val totalBytes = range.totalBytes
                ?: return ViewerVideoPrefetchPlan(ViewerVideoPrefetchAction.SKIP)
            val isCompleteRepresentation = range.startByte == 0L &&
                totalBytes > 0L &&
                range.endByte == totalBytes - 1L &&
                (normalizedLength == null || normalizedLength == totalBytes)
            if (!isCompleteRepresentation || totalBytes > maxBytes) {
                ViewerVideoPrefetchPlan(ViewerVideoPrefetchAction.SKIP)
            } else {
                ViewerVideoPrefetchPlan(
                    action = ViewerVideoPrefetchAction.DOWNLOAD_BOUNDED,
                    expectedBytes = totalBytes,
                )
            }
        }

        else -> ViewerVideoPrefetchPlan(ViewerVideoPrefetchAction.UNAVAILABLE)
    }
}

internal suspend fun copyViewerVideoBodyBounded(
    input: InputStream,
    output: OutputStream,
    maxBytes: Long = VIEWER_VIDEO_PREFETCH_MAX_BYTES,
): ViewerVideoCopyResult {
    require(maxBytes > 0L) { "Viewer video prefetch limit must be positive" }
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var bytesWritten = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        val remaining = maxBytes - bytesWritten
        val requested = minOf(buffer.size.toLong(), remaining + 1L).toInt()
        val count = input.read(buffer, 0, requested)
        if (count < 0) {
            return ViewerVideoCopyResult(ViewerVideoCopyState.COMPLETE, bytesWritten)
        }
        if (count == 0) continue
        currentCoroutineContext().ensureActive()
        if (count.toLong() > remaining) {
            if (remaining > 0L) {
                output.write(buffer, 0, remaining.toInt())
                bytesWritten += remaining
            }
            return ViewerVideoCopyResult(ViewerVideoCopyState.LIMIT_EXCEEDED, bytesWritten)
        }
        output.write(buffer, 0, count)
        bytesWritten += count
    }
}

internal fun resolveViewerVideoPlaybackLocation(
    context: Context,
    media: ImageRef,
): String? {
    val localPath = media.localPath?.takeIf(String::isNotBlank)
    val remoteUrl = media.url?.takeIf(String::isNotBlank)
    val cached = remoteUrl
        ?.takeIf(String::isHttpLocation)
        ?.let { url -> boundedViewerVideoCacheFile(context, url, media.mime) }
    return selectViewerVideoPlaybackLocation(
        localPath = localPath,
        remoteUrl = remoteUrl,
        cachedPath = cached?.absolutePath,
        cachedBytes = cached?.takeIf(File::exists)?.length() ?: 0L,
    )
}

internal fun selectViewerVideoPlaybackLocation(
    localPath: String?,
    remoteUrl: String?,
    cachedPath: String?,
    cachedBytes: Long,
): String? {
    localPath?.takeIf(String::isNotBlank)?.let { return it }
    val remote = remoteUrl?.takeIf(String::isNotBlank) ?: return null
    return cachedPath?.takeIf { path -> path.isNotBlank() && cachedBytes > 0L } ?: remote
}

private suspend fun prefetchRemoteViewerVideo(
    context: Context,
    remoteUrl: String,
    mime: String?,
    headers: Map<String, String>,
): Boolean {
    val output = boundedViewerVideoCacheFile(context, remoteUrl, mime)
    if (output.exists() && output.length() > 0L) {
        output.setLastModified(System.currentTimeMillis())
        trimBoundedViewerVideoCache(context)
        return true
    }

    val connection = URL(remoteUrl).openConnection() as? HttpURLConnection ?: return false
    val temp = File(output.absolutePath + ".part")
    try {
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 12_000
        connection.readTimeout = 24_000
        headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        connection.setRequestProperty("Accept-Encoding", "identity")
        connection.setRequestProperty(
            "Range",
            "bytes=0-${VIEWER_VIDEO_PREFETCH_MAX_BYTES - 1L}",
        )

        val statusCode = connection.responseCode
        currentCoroutineContext().ensureActive()
        val plan = planViewerVideoPrefetch(
            statusCode = statusCode,
            contentLength = connection.contentLengthLong.takeIf { length -> length >= 0L },
            contentRange = connection.getHeaderField("Content-Range"),
        )
        when (plan.action) {
            ViewerVideoPrefetchAction.SKIP -> return true
            ViewerVideoPrefetchAction.UNAVAILABLE -> return false
            ViewerVideoPrefetchAction.DOWNLOAD_BOUNDED -> Unit
        }

        output.parentFile?.mkdirs()
        if (temp.exists()) temp.delete()
        val copyResult = connection.inputStream.use { input ->
            temp.outputStream().use { target ->
                copyViewerVideoBodyBounded(input, target)
            }
        }
        if (copyResult.state == ViewerVideoCopyState.LIMIT_EXCEEDED) return true
        if (copyResult.bytesWritten <= 0L) return false
        if (plan.expectedBytes != null && copyResult.bytesWritten != plan.expectedBytes) return false

        if (output.exists() && !output.delete()) return false
        if (!temp.renameTo(output)) return false
        output.setLastModified(System.currentTimeMillis())
        trimBoundedViewerVideoCache(context)
        return true
    } finally {
        connection.disconnect()
        if (temp.exists()) temp.delete()
    }
}

private fun parseViewerVideoContentRange(value: String?): ViewerVideoContentRange? {
    val match = value
        ?.trim()
        ?.let(VIEWER_VIDEO_CONTENT_RANGE::matchEntire)
        ?: return null
    val start = match.groupValues[1].toLongOrNull() ?: return null
    val end = match.groupValues[2].toLongOrNull() ?: return null
    val total = match.groupValues[3]
        .takeUnless { token -> token == "*" }
        ?.toLongOrNull()
    if (start < 0L || end < start || (total != null && total <= end)) return null
    return ViewerVideoContentRange(startByte = start, endByte = end, totalBytes = total)
}

private fun boundedViewerVideoCacheFile(
    context: Context,
    remoteUrl: String,
    mime: String?,
): File {
    val guessedName = URLUtil.guessFileName(remoteUrl, null, mime)
    val extension = guessedName.substringAfterLast('.', "").ifBlank { "bin" }
    val key = sha256ViewerVideoUrl(remoteUrl)
    return File(viewerVideoCacheDirectory(context), "$key.$extension")
}

private fun trimBoundedViewerVideoCache(context: Context) {
    val files = viewerVideoCacheDirectory(context).listFiles()
        ?.filter { file -> file.isFile && !file.name.endsWith(".part") }
        ?.toMutableList()
        ?: return
    var totalBytes = files.sumOf(File::length)
    var fileCount = files.size
    if (
        fileCount <= BOUNDED_VIEWER_VIDEO_CACHE_MAX_FILES &&
        totalBytes <= BOUNDED_VIEWER_VIDEO_CACHE_MAX_BYTES
    ) {
        return
    }
    files.sortBy(File::lastModified)
    for (file in files) {
        if (
            fileCount <= BOUNDED_VIEWER_VIDEO_CACHE_MAX_FILES &&
            totalBytes <= BOUNDED_VIEWER_VIDEO_CACHE_MAX_BYTES
        ) {
            break
        }
        val bytes = file.length()
        if (file.delete()) {
            fileCount -= 1
            totalBytes = (totalBytes - bytes).coerceAtLeast(0L)
        }
    }
}

private fun viewerVideoCacheDirectory(context: Context): File {
    return File(context.cacheDir, VIEWER_VIDEO_CACHE_DIRECTORY)
}

private fun sha256ViewerVideoUrl(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    return buildString(digest.size * 2) {
        digest.forEach { byte ->
            append(((byte.toInt() ushr 4) and 0xF).toString(16))
            append((byte.toInt() and 0xF).toString(16))
        }
    }
}

private fun String.isHttpLocation(): Boolean {
    return startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)
}

private data class ViewerVideoContentRange(
    val startByte: Long,
    val endByte: Long,
    val totalBytes: Long?,
)

private const val VIEWER_VIDEO_CACHE_DIRECTORY = "theoria_codex/viewer/videos"
private const val BOUNDED_VIEWER_VIDEO_CACHE_MAX_FILES = 80
private const val BOUNDED_VIEWER_VIDEO_CACHE_MAX_BYTES = 750L * 1024L * 1024L
private val VIEWER_VIDEO_CONTENT_RANGE = Regex(
    "bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)",
    RegexOption.IGNORE_CASE,
)
