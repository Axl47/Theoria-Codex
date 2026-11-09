package com.theoriacodex.app.media

import android.content.Context
import com.theoriacodex.app.source.requestHeaders
import com.theoriacodex.app.viewer.PixivUgoiraClient
import com.theoriacodex.data.repository.SettingsRepository
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.PIXIV_UGOIRA_MIME
import com.theoriacodex.domain.model.Post
import com.theoriacodex.app.network.appHttpClients
import com.theoriacodex.sources.http.await
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Provider resolution and network policy are shared by automatic saves and explicit collection downloads.
 * [copyCachedVideo] copies a video the player already cached in full, so saving a watched video skips the download.
 */
class OfflineMediaAcquisition(
    private val context: Context,
    private val registry: SourceAdapterRegistry,
    private val settings: SettingsRepository,
    private val ugoira: PixivUgoiraClient,
    private val copyCachedVideo: suspend (url: String, headers: Map<String, String>, destination: File) -> Boolean,
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
        if (media.mime != PIXIV_UGOIRA_MIME && copyFromMediaCaches(post, media, destination)) return
        checkNetwork()
        if (media.mime == PIXIV_UGOIRA_MIME) {
            ugoira.copyArchiveForOffline(post.id.sourcePostId, destination)
        } else {
            val url = media.url?.takeIf(String::isNotBlank) ?: throw IOException("No full media URL is available")
            downloadMediaFile(url, post.id.source.requestHeaders(), destination, client = context.appHttpClients.downloads)
        }
    }

    /** Reuses the exact bytes Coil or the video player already downloaded for this URL; no network policy applies. */
    private suspend fun copyFromMediaCaches(post: Post, media: ImageRef, destination: File): Boolean {
        val url = media.url?.takeIf(String::isNotBlank) ?: return false
        return withContext(Dispatchers.IO) {
            runCatchingPreservingCancellation {
                val copied = copyCachedMediaBytes(context, post.id.source, url, destination) ||
                    copyCachedVideo(url, post.id.source.requestHeaders(), destination)
                copied && run { validateOfflineMediaFile(destination); true }
            }.getOrDefault(false)
        }
    }

    private suspend fun checkNetwork() {
        val preferences = settings.observeSettings().first().cache
        if (context.animationExportNetworkBlock(preferences) != null) {
            throw IOException("Connect to an allowed network or update download preferences")
        }
    }
}

/**
 * Streams a media response to a staging file with bounded memory; callers publish only a complete,
 * recognized media file. Offline saves use the 2 GiB default; caches pass their own [maxBytes].
 */
internal suspend fun downloadMediaFile(
    url: String,
    headers: Map<String, String>,
    destination: File,
    maxBytes: Long = MAX_OFFLINE_MEDIA_BYTES,
    client: OkHttpClient,
) {
    val parsed = requireNotNull(url.toHttpUrlOrNull()) { "Unsupported media URL" }
    val request = Request.Builder().url(parsed).apply {
        headers.forEach { (name, value) -> header(name, value) }
        header("Accept-Encoding", "identity")
    }.build()
    client.newCall(request).await { response ->
        if (response.code != MEDIA_HTTP_OK) throw IOException("Media download returned HTTP ${response.code}")
        val contentType = response.header("Content-Type").orEmpty().substringBefore(';').lowercase()
        if (contentType.startsWith("text/") || contentType.contains("json") || contentType.contains("html")) {
            throw IOException("The provider returned a page instead of media")
        }
        val expected = response.body.contentLength()
        if (expected > maxBytes) throw mediaTooLarge(maxBytes)
        val written = copyMediaResponse(response.body.byteStream(), destination, maxBytes)
        if (written <= 0 || expected >= 0 && expected != written) {
            throw IOException("The media download was incomplete")
        }
        validateOfflineMediaFile(destination)
    }
}

private fun copyMediaResponse(body: InputStream, destination: File, maxBytes: Long): Long {
    var written = 0L
    body.use { input ->
        FileOutputStream(destination).use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                written += count
                if (written > maxBytes) throw mediaTooLarge(maxBytes)
                output.write(buffer, 0, count)
            }
            output.fd.sync()
        }
    }
    return written
}

private const val MEDIA_HTTP_OK = 200

private fun mediaTooLarge(maxBytes: Long) = IOException(
    if (maxBytes == MAX_OFFLINE_MEDIA_BYTES) "This file exceeds the 2 GiB offline limit" else "Media exceeds its byte limit",
)

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
