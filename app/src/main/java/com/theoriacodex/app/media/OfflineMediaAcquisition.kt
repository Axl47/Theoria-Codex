package com.theoriacodex.app.media

import android.content.Context
import com.theoriacodex.app.source.requestHeaders
import com.theoriacodex.app.viewer.PixivUgoiraClient
import com.theoriacodex.data.repository.SettingsRepository
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.PIXIV_UGOIRA_MIME
import com.theoriacodex.domain.model.Post
import com.theoriacodex.sources.http.executeCancellableHttpConnection
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Provider resolution and network policy are shared by automatic saves and explicit collection downloads. */
class OfflineMediaAcquisition(
    private val context: Context,
    private val registry: SourceAdapterRegistry,
    private val settings: SettingsRepository,
    private val ugoira: PixivUgoiraClient,
) {
    suspend fun resolve(post: Post): Post {
        checkNetwork()
        return registry.adapterFor(post.id.source)?.resolvePost(post.id)
            ?: throw IOException("The full post could not be loaded")
    }

    suspend fun selectMedia(post: Post): List<ImageRef> {
        val preferences = settings.observeSettings().first().cache
        val media = post.media.ifEmpty { listOfNotNull(post.full) }
        check(media.isNotEmpty()) { "The provider returned no full media" }
        return media.map { ref ->
            ref.copy(url = normalizeMediaUrl(post.id.source, ref.url))
                .withVideoQuality(preferences.downloadQuality, context.isMediaNetworkMetered())
        }
    }

    suspend fun acquire(post: Post, media: ImageRef, destination: File) {
        val local = media.localPath?.let(::File)?.takeIf { it.isFile && it.length() > 0L }
        if (local != null && media.mime != PIXIV_UGOIRA_MIME) {
            withContext(Dispatchers.IO) {
                local.copyTo(destination, overwrite = true)
                validateOfflineMediaFile(destination)
            }
            return
        }
        checkNetwork()
        if (media.mime == PIXIV_UGOIRA_MIME) {
            ugoira.copyArchiveForOffline(post.id.sourcePostId, destination)
        } else {
            val url = media.url?.takeIf(String::isNotBlank) ?: throw IOException("No full media URL is available")
            downloadOfflineMedia(url, post.id.source.requestHeaders(), destination)
        }
    }

    private suspend fun checkNetwork() {
        val preferences = settings.observeSettings().first().cache
        if (context.animationExportNetworkBlock(preferences) != null) {
            throw IOException("Connect to an allowed network or update download preferences")
        }
    }
}

/** Stream to a staging file with bounded memory; the store publishes only a complete response. */
internal suspend fun downloadOfflineMedia(
    url: String,
    headers: Map<String, String>,
    destination: File,
    openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    val parsed = URL(url)
    require(parsed.protocol == "https" || parsed.protocol == "http") { "Unsupported media URL" }
    val connection = openConnection(parsed).apply {
        connectTimeout = 12_000
        readTimeout = 30_000
        instanceFollowRedirects = true
        useCaches = false
        headers.forEach { (name, value) -> setRequestProperty(name, value) }
        setRequestProperty("Accept-Encoding", "identity")
    }
    executeCancellableHttpConnection(connection) {
        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            throw IOException("Media download returned HTTP ${connection.responseCode}")
        }
        val contentType = connection.contentType.orEmpty().substringBefore(';').lowercase()
        if (contentType.startsWith("text/") || contentType.contains("json") || contentType.contains("html")) {
            throw IOException("The provider returned a page instead of media")
        }
        val expected = connection.getHeaderFieldLong("Content-Length", -1L)
        if (expected > MAX_OFFLINE_MEDIA_BYTES) throw IOException("This file exceeds the 2 GiB offline limit")
        val written = copyOfflineResponse(connection, destination)
        if (written <= 0 || expected >= 0 && expected != written) {
            throw IOException("The media download was incomplete")
        }
        validateOfflineMediaFile(destination)
    }
}

private fun copyOfflineResponse(connection: HttpURLConnection, destination: File): Long {
    var written = 0L
    connection.inputStream.use { input ->
        FileOutputStream(destination).use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
                val count = input.read(buffer)
                if (count < 0) break
                written += count
                if (written > MAX_OFFLINE_MEDIA_BYTES) throw IOException("This file exceeds the 2 GiB offline limit")
                output.write(buffer, 0, count)
            }
            output.fd.sync()
        }
    }
    return written
}

/** A successful HTML/error response must never become an apparently available offline image. */
internal fun validateOfflineMediaFile(file: File) {
    val header = file.inputStream().use { input ->
        val bytes = ByteArray(16)
        val length = input.read(bytes)
        bytes.take(length.coerceAtLeast(0)).map { it.toInt() and 0xff }
    }
    val imageSignatures = listOf(
        listOf(0xff, 0xd8, 0xff), // JPEG
        listOf(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a), // PNG/APNG
        listOf(0x47, 0x49, 0x46, 0x38), // GIF
        listOf(0x42, 0x4d), // BMP
        listOf(0x1a, 0x45, 0xdf, 0xa3), // WebM/Matroska
        listOf(0x46, 0x4c, 0x56), // FLV
    )
    val recognized = imageSignatures.any { header.take(it.size) == it } ||
        header.drop(4).take(4) == listOf(0x66, 0x74, 0x79, 0x70) || // MP4/AVIF/HEIF
        header.take(4) == listOf(0x52, 0x49, 0x46, 0x46) &&
        header.drop(8).take(4) == listOf(0x57, 0x45, 0x42, 0x50) // WebP
    if (!recognized) throw IOException("The downloaded file is not supported image or video media")
}

private const val MAX_OFFLINE_MEDIA_BYTES = 2L * 1024 * 1024 * 1024
