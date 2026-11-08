package com.theoriacodex.app.viewer

import android.content.Context
import com.theoriacodex.app.media.copyCachedMediaBytes
import com.theoriacodex.app.media.downloadMediaFile
import com.theoriacodex.app.media.normalizeMediaUrl
import com.theoriacodex.app.source.requestHeaders
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.SourceKey
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Raw GIF cache shared by lookahead, Movie decoding and Coil decoder fallback. Each acquisition is
 * filled into a `.tmp` staging file first, so prefetch can warm an entry without holding it in memory.
 */
internal class ViewerGifByteStore(private val directory: File) {
    private val locks = List(16) { Mutex() }
    private val writeMutex = Mutex()
    private var generation = 0L

    /** Returns the cached bytes for [key], filling the entry once through [fill] on a miss. */
    suspend fun load(key: String, fill: suspend (File) -> Unit): ByteArray =
        withEntry(key) { destination -> read(destination) ?: store(destination, fill, File::readBytes) }

    /** Ensures [key] is cached and returns its length without reading the file into memory. */
    suspend fun warm(key: String, fill: suspend (File) -> Unit): Long = withEntry(key) { destination ->
        if (isFresh(destination)) destination.length() else store(destination, fill, File::length)
    }

    private suspend fun <T> withEntry(key: String, block: suspend (File) -> T): T = withContext(Dispatchers.IO) {
        val name = entryName(key)
        locks[(name.hashCode() and Int.MAX_VALUE) % locks.size].withLock { block(File(directory, name)) }
    }

    /** Publishes a filled staging file unless the cache was cleared meanwhile; [result] reads it first. */
    private suspend fun <T> store(destination: File, fill: suspend (File) -> Unit, result: (File) -> T): T {
        val admittedGeneration = writeMutex.withLock { generation }
        directory.mkdirs()
        val temporary = File.createTempFile("gif-", TEMPORARY_SUFFIX, directory)
        try {
            fill(temporary)
            require(temporary.length() <= MAX_VIEWER_GIF_BYTES) { "GIF exceeds playback byte limit" }
            val value = result(temporary)
            runCatchingPreservingCancellation { writeMutex.withLock cacheWrite@ {
                if (generation != admittedGeneration) return@cacheWrite
                if (!temporary.renameTo(destination)) throw IOException("Could not cache GIF")
                trim(destination)
            } }
            return value
        } finally {
            temporary.delete()
        }
    }

    suspend fun cachedLocation(key: String): String? = withContext(Dispatchers.IO) {
        File(directory, entryName(key)).takeIf { it.isFile && it.length() in 1..MAX_VIEWER_GIF_BYTES.toLong() }?.path
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            generation++
            entries().forEach(File::delete)
        }
    }

    suspend fun byteCount(): Long = withContext(Dispatchers.IO) {
        writeMutex.withLock { directory.listFiles().orEmpty().filter(File::isFile).sumOf(File::length) }
    }

    private fun isFresh(file: File): Boolean = file.isFile &&
        file.length() in 1..MAX_VIEWER_GIF_BYTES.toLong() &&
        System.currentTimeMillis() - file.lastModified() <= CACHE_MAX_AGE_MS

    private fun read(file: File): ByteArray? = if (isFresh(file)) runCatching { file.readBytes() }.getOrNull() else null

    /** Published entries; in-flight staging files belong to their acquisition until it finishes. */
    private fun entries(): List<File> =
        directory.listFiles().orEmpty().filter { it.isFile && !it.name.endsWith(TEMPORARY_SUFFIX) }

    private fun entryName(key: String): String = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        .toHexString()

    private fun trim(retained: File) {
        val files = entries().sortedBy(File::lastModified)
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
        const val TEMPORARY_SUFFIX = ".tmp"
    }
}

internal suspend fun loadRemoteViewerGifBytes(context: Context, source: SourceKey, location: String): ByteArray {
    val url = normalizeMediaUrl(source, location) ?: location
    return gifByteStore(context).load("${source.id}:$url") { file -> fillRemoteGif(context, source, url, file) }
}

/** Prefetch path: caches the GIF on disk and reports its size without loading it into memory. */
internal suspend fun warmRemoteViewerGif(context: Context, source: SourceKey, location: String): Long {
    val url = normalizeMediaUrl(source, location) ?: location
    return gifByteStore(context).warm("${source.id}:$url") { file -> fillRemoteGif(context, source, url, file) }
}

private suspend fun fillRemoteGif(context: Context, source: SourceKey, url: String, destination: File) {
    // Feed images may already have warmed Coil's raw disk entry.
    if (copyCachedMediaBytes(context, source, url, destination, MAX_VIEWER_GIF_BYTES.toLong())) return
    // The connection's read timeout catches stalls; this bound must leave room for large files on slow links.
    withTimeout(GIF_DOWNLOAD_TIMEOUT_MS) {
        downloadMediaFile(url, source.requestHeaders(), destination, MAX_VIEWER_GIF_BYTES.toLong())
    }
}

internal suspend fun cachedViewerGifLocation(context: Context, source: SourceKey, location: String): String =
    gifByteStore(context).cachedLocation("${source.id}:${normalizeMediaUrl(source, location) ?: location}") ?: location

internal suspend fun clearViewerGifCache(context: Context) = gifByteStore(context).clear()

internal suspend fun viewerGifCacheBytes(context: Context): Long = gifByteStore(context).byteCount()

private fun gifByteStore(context: Context): ViewerGifByteStore = synchronized(gifStores) {
    val directory = File(context.cacheDir, "viewer-gif-bytes")
    gifStores.getOrPut(directory) { ViewerGifByteStore(directory) }.also {
        while (gifStores.size > 4) gifStores.remove(gifStores.keys.first())
    }
}

private val gifStores = linkedMapOf<File, ViewerGifByteStore>()
internal const val MAX_VIEWER_GIF_BYTES = 64 * 1024 * 1024
private const val GIF_DOWNLOAD_TIMEOUT_MS = 120_000L
