package com.theoriacodex.sources.hitomi

import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

internal data class HitomiGalleryManifest(
    val gallery: JsonObject,
    val payloadBytes: Long,
)

internal class HitomiGalleryManifestStore(
    maxBytes: Long,
    private val ttlMillis: Long,
    private val nowMillis: () -> Long,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val lock = Any()
    private val flightScope = CoroutineScope(
        scope.coroutineContext + SupervisorJob(),
    )
    private val cache = HitomiByteBudgetCache<Int, CachedManifest>(
        maxBytes = maxBytes,
        weigh = { _, entry -> entry.manifest.payloadBytes + HITOMI_MANIFEST_ENTRY_OVERHEAD_BYTES },
    )
    private val flights = mutableMapOf<Int, Deferred<HitomiGalleryManifest?>>()

    init {
        require(ttlMillis > 0L) { "Hitomi gallery manifest TTL must be positive" }
    }

    suspend fun getOrLoad(
        galleryId: Int,
        loader: suspend () -> HitomiGalleryManifest?,
    ): JsonObject? {
        cache.get(galleryId)?.let { cached ->
            if (cached.expiresAtEpochMs > nowMillis()) return cached.manifest.gallery
            cache.remove(galleryId)
        }
        val deferred = synchronized(lock) {
            flights[galleryId] ?: flightScope.async { loader() }.also { flight ->
                flights[galleryId] = flight
                flight.invokeOnCompletion {
                    synchronized(lock) {
                        if (flights[galleryId] === flight) flights.remove(galleryId)
                    }
                }
            }
        }
        return deferred.await()?.also { manifest ->
            cache.put(
                galleryId,
                CachedManifest(
                    manifest = manifest,
                    expiresAtEpochMs = nowMillis() + ttlMillis,
                ),
            )
        }?.gallery
    }

    internal fun snapshot(): HitomiByteBudgetCacheSnapshot<Int> = cache.snapshot()

    private data class CachedManifest(
        val manifest: HitomiGalleryManifest,
        val expiresAtEpochMs: Long,
    )
}

private const val HITOMI_MANIFEST_ENTRY_OVERHEAD_BYTES = 128L
