package com.theoriacodex.sources.pixiv

import com.theoriacodex.sources.common.BoundedLruCache

/**
 * Recent successful `/v1/ugoira/metadata` bodies by illust ID. One process-wide instance is shared by the
 * adapter's duration lookup and the ugoira player, so a visible ugoira card asks Pixiv's rate-limited API
 * for its metadata once instead of once per consumer.
 */
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
