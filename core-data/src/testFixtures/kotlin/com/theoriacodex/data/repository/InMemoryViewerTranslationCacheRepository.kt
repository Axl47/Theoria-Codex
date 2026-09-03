package com.theoriacodex.data.repository

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class InMemoryViewerTranslationCacheRepository(
    private val maxEntries: Int = DEFAULT_VIEWER_TRANSLATION_CACHE_ENTRY_LIMIT,
    private val ttlMs: Long = DEFAULT_VIEWER_TRANSLATION_CACHE_TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewerTranslationCacheRepository {
    private val lock = Mutex()
    private val records = linkedMapOf<ViewerTranslationCacheKey, Record>()

    init {
        require(maxEntries > 0) { "Translation cache entry limit must be positive" }
        require(ttlMs > 0L) { "Translation cache TTL must be positive" }
    }

    override suspend fun getAll(
        keys: Set<ViewerTranslationCacheKey>,
    ): Map<ViewerTranslationCacheKey, String> = lock.withLock {
        val now = clock()
        buildMap {
            keys.forEach { key ->
                val record = records[key] ?: return@forEach
                if (record.expiresAtEpochMs <= now) {
                    records.remove(key)
                } else {
                    records[key] = record.copy(lastUsedAtEpochMs = now)
                    put(key, record.translatedText)
                }
            }
        }
    }

    override suspend fun putAll(translations: Map<ViewerTranslationCacheKey, String>) {
        lock.withLock {
            val now = clock()
            translations.forEach { (key, translatedText) ->
                require(translatedText.isNotBlank()) { "Cached translation must not be blank" }
                records[key] = Record(
                    translatedText = translatedText,
                    expiresAtEpochMs = now + ttlMs,
                    lastUsedAtEpochMs = now,
                )
            }
            val retained = records.entries
                .sortedByDescending { entry -> entry.value.lastUsedAtEpochMs }
                .take(maxEntries)
                .mapTo(hashSetOf()) { entry -> entry.key }
            records.keys.retainAll(retained)
        }
    }

    private data class Record(
        val translatedText: String,
        val expiresAtEpochMs: Long,
        val lastUsedAtEpochMs: Long,
    )
}
