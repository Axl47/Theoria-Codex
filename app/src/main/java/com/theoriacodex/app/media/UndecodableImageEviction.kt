package com.theoriacodex.app.media

import coil.EventListener
import coil.disk.DiskCache
import coil.network.HttpException
import coil.request.ErrorResult
import coil.request.ImageRequest
import java.io.IOException

/**
 * Cached images are served without revalidation, so a cached body that cannot be decoded (an HTML error
 * page sent with 200, a truncated file) would fail every later load. A failure that is not a network or
 * HTTP error drops that request's disk entry, so the next load downloads the file again.
 */
internal class UndecodableImageEviction(private val diskCache: DiskCache) : EventListener {
    override fun onError(request: ImageRequest, result: ErrorResult) {
        if (result.throwable is IOException || result.throwable is HttpException) return
        val key = request.diskCacheKey ?: (request.data as? String) ?: return
        diskCache.remove(key)
    }
}
