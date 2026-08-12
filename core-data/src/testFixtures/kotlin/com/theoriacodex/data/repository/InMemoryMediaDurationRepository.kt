package com.theoriacodex.data.repository

import java.util.LinkedHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class InMemoryMediaDurationRepository(
    private val maxEntries: Int = DEFAULT_MEDIA_DURATION_ENTRY_LIMIT,
    private val clock: () -> Long = System::currentTimeMillis,
) : MediaDurationRepository {
    private val lock = Mutex()
    private val records = LinkedHashMap<StoredMediaDurationKey, Record>()

    init {
        require(maxEntries > 0) { "Media duration entry limit must be positive" }
    }

    override suspend fun get(key: StoredMediaDurationKey): StoredMediaDurationState? = lock.withLock {
        val record = records[key] ?: return@withLock null
        val state = record.state
        if (state is StoredMediaDurationState.RetryableFailure && state.retryAtEpochMs <= clock()) {
            records.remove(key)
            null
        } else {
            state
        }
    }

    override suspend fun put(key: StoredMediaDurationKey, state: StoredMediaDurationState) {
        lock.withLock {
            records[key] = Record(state = state, updatedAtEpochMs = clock())
            pruneLocked()
        }
    }

    private fun pruneLocked() {
        if (records.size <= maxEntries) return
        val retainedKeys = records.entries
            .sortedWith(
                compareByDescending<Map.Entry<StoredMediaDurationKey, Record>> { entry ->
                    entry.value.updatedAtEpochMs
                }.thenBy { entry -> entry.key.postId.source.name }
                    .thenBy { entry -> entry.key.postId.sourcePostId }
                    .thenBy { entry -> entry.key.mediaFingerprint },
            )
            .take(maxEntries)
            .mapTo(hashSetOf()) { entry -> entry.key }
        records.keys.retainAll(retainedKeys)
    }

    private data class Record(
        val state: StoredMediaDurationState,
        val updatedAtEpochMs: Long,
    )
}
