package com.theoriacodex.sources.hitomi

import com.theoriacodex.domain.coroutines.SharedFlights

internal class HitomiGlobalIndexCache(
    maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    private val lock = Any()
    private val entries = HitomiByteBudgetCache<String, IntArray>(
        maxBytes = maxBytes,
        weigh = { key, ids -> key.hitomiUtf8ByteWeight() + ids.hitomiByteWeight() },
    )
    private val flights = SharedFlights<VersionedKey, IntArray>()
    private var activeVersion: String? = null

    fun get(key: String): IntArray? = entries.get(key)

    suspend fun getOrLoad(
        version: String,
        key: String,
        loader: suspend () -> IntArray,
    ): IntArray {
        activate(version, key)?.let { return it }
        return flights.share(VersionedKey(version, key)) {
            synchronized(lock) { entries.get(key)?.takeIf { activeVersion == version } } ?: loader().also { loaded ->
                synchronized(lock) { if (activeVersion == version) entries.put(key, loaded) }
            }
        }
    }

    internal fun snapshot(): HitomiGlobalIndexCacheSnapshot {
        val version = synchronized(lock) { activeVersion }
        val cached = entries.snapshot()
        return HitomiGlobalIndexCacheSnapshot(
            maxBytes = cached.maxBytes,
            activeVersion = version,
            keysInLruOrder = cached.keysInLruOrder,
            weightsInLruOrder = cached.weightsInLruOrder,
            cachedBytes = cached.cachedBytes,
            inFlightLoads = flights.inFlightCount,
        )
    }

    private fun activate(version: String, key: String): IntArray? = synchronized(lock) {
        if (activeVersion != version) {
            entries.clear()
            activeVersion = version
        }
        entries.get(key)
    }

    private data class VersionedKey(
        val version: String,
        val key: String,
    )

    companion object {
        const val DEFAULT_MAX_BYTES: Long = 32L * 1024L * 1024L
    }
}

internal data class HitomiGlobalIndexCacheSnapshot(
    val maxBytes: Long,
    val activeVersion: String?,
    val keysInLruOrder: List<String>,
    val weightsInLruOrder: List<Pair<String, Long>>,
    val cachedBytes: Long,
    val inFlightLoads: Int,
)
