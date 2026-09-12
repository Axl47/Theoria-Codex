package com.theoriacodex.app.viewer

import android.content.Context
import coil.imageLoader
import com.theoriacodex.app.media.normalizeMediaUrl
import com.theoriacodex.app.source.requestHeaders
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.http.DefaultSourceHttpClient
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Raw GIF cache shared by lookahead, Movie decoding and Coil decoder fallback. */
internal class ViewerGifByteStore(private val directory: File) {
    private val locks = List(16) { Mutex() }
    private val writeMutex = Mutex()

    suspend fun load(key: String, fetch: suspend () -> ByteArray): ByteArray = withContext(Dispatchers.IO) {
        val name = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        locks[(name.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            val destination = File(directory, name)
            read(destination)?.let { return@withLock it }
            val bytes = fetch()
            require(bytes.size <= MAX_VIEWER_GIF_BYTES) { "GIF exceeds playback byte limit" }
            runCatchingPreservingCancellation { writeMutex.withLock {
                directory.mkdirs()
                val temporary = File.createTempFile("gif-", ".tmp", directory)
                try {
                    temporary.writeBytes(bytes)
                    if (!temporary.renameTo(destination)) throw IOException("Could not cache GIF")
                    trim(destination)
                } finally {
                    temporary.delete()
                }
            } }
            bytes
        }
    }

    suspend fun cachedLocation(key: String): String? = withContext(Dispatchers.IO) {
        val name = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        File(directory, name).takeIf { it.isFile && it.length() in 1..MAX_VIEWER_GIF_BYTES.toLong() }?.path
    }

    private fun read(file: File): ByteArray? {
        if (!file.isFile || file.length() !in 1..MAX_VIEWER_GIF_BYTES.toLong()) return null
        if (System.currentTimeMillis() - file.lastModified() > CACHE_MAX_AGE_MS) return null
        return runCatching { file.readBytes() }.getOrNull()
    }

    private fun trim(retained: File) {
        val files = directory.listFiles().orEmpty().filter { it.isFile }.sortedBy(File::lastModified)
        var bytes = files.sumOf(File::length)
        var count = files.size
        files.filterNot { it == retained }.forEach { file ->
            if (bytes > CACHE_MAX_BYTES || count > CACHE_MAX_FILES) {
                val length = file.length()
                if (file.delete()) { bytes -= length; count-- }
            }
        }
    }

    private companion object {
        const val CACHE_MAX_BYTES = 128L * 1024 * 1024
        const val CACHE_MAX_FILES = 64
        const val CACHE_MAX_AGE_MS = 24 * 60 * 60_000L
    }
}

@OptIn(coil.annotation.ExperimentalCoilApi::class)
internal suspend fun loadRemoteViewerGifBytes(context: Context, source: SourceKey, location: String): ByteArray {
    val url = normalizeMediaUrl(source, location) ?: location
    return gifByteStore(context).load("${source.name}:$url") {
        // Feed images may already have warmed Coil's raw disk entry. Never write Coil metadata ourselves.
        val warmed = context.imageLoader.diskCache?.openSnapshot(url)?.use { snapshot ->
            snapshot.data.toFile().takeIf { it.length() in 1..MAX_VIEWER_GIF_BYTES.toLong() }?.readBytes()
        }
        warmed ?: withTimeout(20_000L) {
            val response = gifHttpClient.getBytes(url, headers = source.requestHeaders(), maxBodyBytes = MAX_VIEWER_GIF_BYTES)
            if (response.statusCode !in 200..299) throw IOException("GIF HTTP ${response.statusCode}")
            response.body
        }
    }
}

internal suspend fun cachedViewerGifLocation(context: Context, source: SourceKey, location: String): String =
    gifByteStore(context).cachedLocation("${source.name}:${normalizeMediaUrl(source, location) ?: location}") ?: location

private fun gifByteStore(context: Context): ViewerGifByteStore = synchronized(gifStores) {
    val directory = File(context.cacheDir, "viewer-gif-bytes")
    gifStores.getOrPut(directory) { ViewerGifByteStore(directory) }.also {
        while (gifStores.size > 4) gifStores.remove(gifStores.keys.first())
    }
}

private val gifStores = linkedMapOf<File, ViewerGifByteStore>()
private val gifHttpClient = DefaultSourceHttpClient(maxRetries = 0)
internal const val MAX_VIEWER_GIF_BYTES = 64 * 1024 * 1024
