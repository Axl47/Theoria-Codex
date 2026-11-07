package com.theoriacodex.sources.pixiv

/**
 * Recent successful `/v1/ugoira/metadata` bodies by illust ID. One process-wide instance is shared by the
 * adapter's duration lookup and the ugoira player, so a visible ugoira card asks Pixiv's rate-limited API
 * for its metadata once instead of once per consumer.
 */
class PixivUgoiraMetadataCache(private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {
    private val bodies = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > maxEntries
    }

    fun get(illustId: String): String? = synchronized(bodies) { bodies[illustId] }

    fun put(illustId: String, body: String) {
        synchronized(bodies) { bodies[illustId] = body }
    }

    private companion object {
        const val DEFAULT_MAX_ENTRIES = 64
    }
}
