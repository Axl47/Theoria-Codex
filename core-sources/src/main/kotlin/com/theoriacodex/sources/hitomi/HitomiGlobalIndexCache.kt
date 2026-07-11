package com.theoriacodex.sources.hitomi

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class HitomiGlobalIndexCache(
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    private val mutex = Mutex()
    private val entries = LinkedHashMap<String, CacheEntry>(16, 0.75f, true)
    private val inFlight = mutableMapOf<VersionedKey, CompletableDeferred<IntArray>>()
    private var activeVersion: String? = null
    private var cachedBytes: Long = 0L

    init {
        require(maxBytes > 0L) { "Hitomi global index cache must have a positive byte budget" }
    }

    suspend fun get(key: String): IntArray? = mutex.withLock {
        entries[key]?.ids
    }

    suspend fun getOrLoad(
        version: String,
        key: String,
        loader: suspend () -> IntArray,
    ): IntArray {
        var ownsLoad = false
        val versionedKey = VersionedKey(version, key)
        val flight = mutex.withLock {
            activateVersion(version)
            entries[key]?.let { cached -> return cached.ids }
            inFlight[versionedKey] ?: CompletableDeferred<IntArray>().also { created ->
                inFlight[versionedKey] = created
                ownsLoad = true
            }
        }
        if (!ownsLoad) return flight.await()

        var loadFailure: Throwable? = null
        try {
            val loaded = loader()
            mutex.withLock {
                if (activeVersion == version) {
                    put(key, loaded)
                }
            }
            flight.complete(loaded)
            return loaded
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            loadFailure = error
            throw error
        } finally {
            finishLoad(versionedKey, flight, loadFailure)
        }
    }

    internal suspend fun snapshot(): HitomiGlobalIndexCacheSnapshot = mutex.withLock {
        HitomiGlobalIndexCacheSnapshot(
            activeVersion = activeVersion,
            keysInLruOrder = entries.keys.toList(),
            cachedBytes = cachedBytes,
            inFlightLoads = inFlight.size,
        )
    }

    private suspend fun finishLoad(
        key: VersionedKey,
        flight: CompletableDeferred<IntArray>,
        failure: Throwable?,
    ) = withContext(NonCancellable) {
        mutex.withLock {
            if (inFlight[key] === flight) {
                inFlight.remove(key)
            }
        }
        when {
            failure != null -> flight.completeExceptionally(failure)
            !flight.isCompleted -> flight.cancel()
        }
    }

    private fun activateVersion(version: String) {
        if (activeVersion == version) return
        entries.clear()
        cachedBytes = 0L
        activeVersion = version
    }

    private fun put(key: String, ids: IntArray) {
        val weight = ids.byteWeight()
        entries.remove(key)?.let { previous -> cachedBytes -= previous.weightBytes }
        if (weight > maxBytes) return

        entries[key] = CacheEntry(ids = ids, weightBytes = weight)
        cachedBytes += weight
        while (cachedBytes > maxBytes) {
            val eldest = entries.entries.iterator().run {
                if (hasNext()) next() else null
            } ?: break
            entries.remove(eldest.key)
            cachedBytes -= eldest.value.weightBytes
        }
    }

    private fun IntArray.byteWeight(): Long {
        return Math.multiplyExact(size.toLong(), Int.SIZE_BYTES.toLong())
    }

    private data class CacheEntry(
        val ids: IntArray,
        val weightBytes: Long,
    )

    private data class VersionedKey(
        val version: String,
        val key: String,
    )

    companion object {
        const val DEFAULT_MAX_BYTES: Long = 32L * 1024L * 1024L
    }
}

internal data class HitomiGlobalIndexCacheSnapshot(
    val activeVersion: String?,
    val keysInLruOrder: List<String>,
    val cachedBytes: Long,
    val inFlightLoads: Int,
)
