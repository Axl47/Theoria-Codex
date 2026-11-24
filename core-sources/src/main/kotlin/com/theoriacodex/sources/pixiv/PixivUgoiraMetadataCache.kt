package com.theoriacodex.sources.pixiv

import com.theoriacodex.sources.common.BoundedLruCache

class PixivUgoiraMetadataCache(maxEntries: Int = DEFAULT_MAX_ENTRIES) {
    private val bodies = BoundedLruCache<String, String>(maxEntries)

    fun get(illustId: String): String? = bodies[illustId]

    fun put(illustId: String, body: String) {
        bodies.update(illustId) { body }
    }

    private companion object {
        const val DEFAULT_MAX_ENTRIES = 64
    }
}
