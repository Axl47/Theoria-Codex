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

internal const val VIDEO_CACHE_CHUNK_BYTES = 2L * 1024L * 1024L

private const val LENGTH_UNSET = C.LENGTH_UNSET.toLong()

private const val RANGES_IGNORED_METADATA = "theoria-ranges-ignored"

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
