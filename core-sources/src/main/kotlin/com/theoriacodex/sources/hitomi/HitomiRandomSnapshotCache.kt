package com.theoriacodex.sources.hitomi

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class HitomiRandomSnapshotCache(
    maxBytes: Long = DEFAULT_MAX_BYTES,
    private val initialReuseTtlMillis: Long = DEFAULT_INITIAL_REUSE_TTL_MILLIS,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val entries = HitomiByteBudgetCache<HitomiRandomSnapshotKey, CachedSnapshot>(
        maxBytes = maxBytes,
        weigh = { key, cached ->
            key.url.hitomiUtf8ByteWeight() +
                key.fingerprint.hitomiUtf8ByteWeight() +
                cached.snapshot.ids.hitomiByteWeight() +
                Long.SIZE_BYTES
        },
    )
    private val inFlightMutex = Mutex()
    private val inFlight = mutableMapOf<String, CompletableDeferred<HitomiNozomiSnapshot>>()

    init {
        require(initialReuseTtlMillis > 0L) { "Hitomi random snapshot TTL must be positive" }
    }

    suspend fun getOrLoad(
        url: String,
        expectedFingerprint: String?,
        loader: suspend () -> HitomiNozomiSnapshot,
    ): HitomiNozomiSnapshot {
        cached(url, expectedFingerprint)?.let { return it }
        var ownsLoad = false
        val flight = inFlightMutex.withLock {
            cached(url, expectedFingerprint)?.let { return it }
            inFlight[url] ?: CompletableDeferred<HitomiNozomiSnapshot>().also { created ->
                inFlight[url] = created
                ownsLoad = true
            }
        }
        if (!ownsLoad) return flight.await().requireFingerprint(expectedFingerprint)

        var failure: Throwable? = null
        try {
            val loaded = loader()
            entries.put(
                HitomiRandomSnapshotKey(url, loaded.fingerprint),
                CachedSnapshot(loaded, nowMillis()),
            )
            flight.complete(loaded)
            return loaded.requireFingerprint(expectedFingerprint)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            finishLoad(url, flight, failure)
        }
    }

    fun snapshot(): HitomiRandomSnapshotCacheSnapshot {
        val cached = entries.snapshot()
        return HitomiRandomSnapshotCacheSnapshot(
            maxBytes = cached.maxBytes,
            cachedBytes = cached.cachedBytes,
            keysInLruOrder = cached.keysInLruOrder,
            weightsInLruOrder = cached.weightsInLruOrder,
        )
    }

    private fun cached(url: String, expectedFingerprint: String?): HitomiNozomiSnapshot? {
        return if (expectedFingerprint != null) {
            entries.get(HitomiRandomSnapshotKey(url, expectedFingerprint))?.snapshot
        } else {
            entries.latestMatching { key -> key.url == url }
                ?.second
                ?.takeIf { cached -> nowMillis() - cached.loadedAtMillis in 0 until initialReuseTtlMillis }
                ?.snapshot
        }
    }

    private fun HitomiNozomiSnapshot.requireFingerprint(expectedFingerprint: String?): HitomiNozomiSnapshot {
        if (expectedFingerprint != null && fingerprint != expectedFingerprint) {
            throw HitomiRandomSnapshotMismatchException()
        }
        return this
    }

    private suspend fun finishLoad(
        url: String,
        flight: CompletableDeferred<HitomiNozomiSnapshot>,
        failure: Throwable?,
    ) = withContext(NonCancellable) {
        inFlightMutex.withLock {
            if (inFlight[url] === flight) inFlight.remove(url)
        }
        when {
            failure != null -> flight.completeExceptionally(failure)
            !flight.isCompleted -> flight.cancel()
        }
    }

    companion object {
        const val DEFAULT_MAX_BYTES: Long = 16L * 1024L * 1024L
        const val DEFAULT_INITIAL_REUSE_TTL_MILLIS: Long = 60_000L
    }

    private data class CachedSnapshot(
        val snapshot: HitomiNozomiSnapshot,
        val loadedAtMillis: Long,
    )
}

internal class HitomiRandomSnapshotMismatchException : IllegalStateException(
    "Hitomi random snapshot did not match the continuation token",
)

internal data class HitomiRandomSnapshotKey(
    val url: String,
    val fingerprint: String,
)

internal data class HitomiNozomiSnapshot(
    val ids: IntArray,
    val fingerprint: String,
)

internal data class HitomiRandomSnapshotCacheSnapshot(
    val maxBytes: Long,
    val cachedBytes: Long,
    val keysInLruOrder: List<HitomiRandomSnapshotKey>,
    val weightsInLruOrder: List<Pair<HitomiRandomSnapshotKey, Long>>,
)
