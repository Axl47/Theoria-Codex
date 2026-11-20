@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.theoriacodex.app.viewer

import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpUtil
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations

/** Each video cache request fills at most this much before the next request continues it. */
internal const val VIDEO_CACHE_CHUNK_BYTES = 2L * 1024L * 1024L

private const val LENGTH_UNSET = C.LENGTH_UNSET.toLong()

/** Cache metadata set when a ranged request got a whole-file response, so that file is read unbounded. */
private const val RANGES_IGNORED_METADATA = "theoria-ranges-ignored"

/**
 * Bounds how much of a stream one cache writer locks. Media3 lets one writer at a time fill a cache range, and a
 * progressive request otherwise locks from its position to the next cached span or the end of the file, so another
 * reader ahead of it reads around the cache and cannot read what it wrote until a 5 MB fragment fills. Feed cards
 * (behind the Viewer) and neighbour prefetch (behind a swipe) use it: each request covers at most [chunkBytes];
 * when it ends, `CacheDataSource` commits what it wrote and opens the next request. The Viewer's own player and scrub
 * previews keep unbounded requests: bounding them only added request gaps and a preview read-ahead into the
 * player's open chunk, which cost later preview frames about 150 ms.
 *
 * Requests never start at the end of a file: a hole is bounded only while more than a chunk of the file is known
 * to remain, or for a file's first request, whose response records the file's length ([RangeRecordingDataSource]).
 * The last stretch is read unbounded, so it ends at the file's natural end. A server that ignores ranges turns
 * bounding off for that file.
 */
internal class BoundedLockCache(
    private val delegate: Cache,
    private val chunkBytes: Long = VIDEO_CACHE_CHUNK_BYTES,
) : Cache by delegate {
    override fun startReadWrite(key: String, position: Long, length: Long): CacheSpan =
        delegate.startReadWrite(key, position, boundedLength(key, position, length))

    override fun startReadWriteNonBlocking(key: String, position: Long, length: Long): CacheSpan? =
        delegate.startReadWriteNonBlocking(key, position, boundedLength(key, position, length))

    private fun boundedLength(key: String, position: Long, length: Long): Long {
        val metadata = delegate.getContentMetadata(key)
        if (metadata.get(RANGES_IGNORED_METADATA, 0L) != 0L) return length
        val contentLength = ContentMetadata.getContentLength(metadata)
        if (contentLength == LENGTH_UNSET && length == LENGTH_UNSET) return chunkBytes
        val toEnd = if (contentLength == LENGTH_UNSET) Long.MAX_VALUE else contentLength - position
        val remaining = minOf(toEnd, if (length == LENGTH_UNSET) Long.MAX_VALUE else length)
        return if (remaining > chunkBytes) chunkBytes else length
    }
}

/**
 * Wraps a video cache's HTTP upstream. A ranged response records what [BoundedLockCache] needs to bound a file's
 * later requests: its full length from `Content-Range`, or, when the server answered with the whole file (or an
 * unknown length), that this file must be read unbounded.
 */
internal class RangeRecordingDataSource(
    private val upstream: DataSource,
    private val cache: Cache,
) : DataSource by upstream {
    override fun open(dataSpec: DataSpec): Long {
        val opened = upstream.open(dataSpec)
        val key = dataSpec.key
        if (key != null && (dataSpec.position != 0L || dataSpec.length != LENGTH_UNSET)) {
            record(key, HttpUtil.getDocumentSize(upstream.responseHeaders.headerValue("Content-Range")))
        }
        return opened
    }

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    private fun record(key: String, documentSize: Long) {
        val metadata = cache.getContentMetadata(key)
        val mutations = ContentMetadataMutations()
        when {
            documentSize == LENGTH_UNSET -> mutations.set(RANGES_IGNORED_METADATA, 1L)
            ContentMetadata.getContentLength(metadata) != documentSize ->
                ContentMetadataMutations.setContentLength(mutations, documentSize)
            else -> return
        }
        cache.applyContentMetadataMutations(key, mutations)
    }

    class Factory(private val upstream: DataSource.Factory, private val cache: Cache) : DataSource.Factory {
        override fun createDataSource(): DataSource = RangeRecordingDataSource(upstream.createDataSource(), cache)
    }
}

private fun Map<String, List<String>>.headerValue(name: String): String? =
    entries.firstOrNull { (header, _) -> header.equals(name, ignoreCase = true) }?.value?.firstOrNull()
