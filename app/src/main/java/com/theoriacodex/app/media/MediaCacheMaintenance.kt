package com.theoriacodex.app.media

import android.content.Context
import coil.imageLoader
import com.theoriacodex.app.viewer.PixivUgoiraClient
import com.theoriacodex.app.viewer.clearViewerGifCache
import com.theoriacodex.app.viewer.videoPlaybackInfrastructure
import com.theoriacodex.app.viewer.viewerGifCacheBytes
import com.theoriacodex.data.repository.CacheRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Disposable owners clear their own stores; pinned offline media is deliberately separate. */
class MediaCacheMaintenance(
    private val context: Context,
    private val cache: CacheRepository,
    private val ugoira: PixivUgoiraClient,
) {
    suspend fun snapshot(): DisposableMediaCacheSnapshot {
        val legacy = cache.observeSnapshot().first()
        return DisposableMediaCacheSnapshot(
            imageBytes = (context.imageLoader.diskCache?.size ?: 0L) + legacy.thumbnailBytes + legacy.fullImageBytes,
            videoBytes = context.videoPlaybackInfrastructure().disposableCacheBytes(),
            gifBytes = viewerGifCacheBytes(context),
            animationBytes = ugoira.disposableCacheBytes(),
        )
    }

    /** Returns false when an active animation kept its archive; callers can offer another clear later. */
    suspend fun clear(): Boolean {
        withContext(Dispatchers.IO) {
            context.imageLoader.diskCache?.clear()
            context.imageLoader.memoryCache?.clear()
        }
        cache.clearThumbnailCache()
        cache.clearFullImageCache()
        context.videoPlaybackInfrastructure().clearDisposableCache()
        clearViewerGifCache(context)
        return ugoira.clearDisposableCache()
    }
}

data class DisposableMediaCacheSnapshot(
    val imageBytes: Long = 0L,
    val videoBytes: Long = 0L,
    val gifBytes: Long = 0L,
    val animationBytes: Long = 0L,
) {
    val totalBytes: Long get() = imageBytes + videoBytes + gifBytes + animationBytes
}
