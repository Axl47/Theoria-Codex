package com.theoriacodex.app.media

import coil.EventListener
import coil.disk.DiskCache
import coil.network.HttpException
import coil.request.ErrorResult
import coil.request.ImageRequest
import java.io.IOException

class UndecodableImageEviction(private val diskCache: DiskCache) : EventListener {
    override fun onError(request: ImageRequest, result: ErrorResult) {
        if (result.throwable is IOException || result.throwable is HttpException) return
        val key = request.diskCacheKey ?: (request.data as? String) ?: return
        diskCache.remove(key)
    }
}
