package com.theoriacodex.sources.hitomi

import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.coroutines.SharedFlights
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/** A parsed gallery record held in memory only; nothing here is written to disk. */
internal data class HitomiGalleryManifest(
    val gallery: JsonObject,
    val payloadBytes: Long,
)

/**
 * Holds recently loaded gallery records for [ttlMillis] within a byte budget. Concurrent loads of one gallery
 * are shared and cancelled only when every caller waiting on them has left; missing and failed loads are not
 * kept.
 */
internal class HitomiGalleryManifestStore(
    maxBytes: Long,
    private val ttlMillis: Long,
    private val nowMillis: () -> Long,
    private val loadTimeoutMs: Long = 15_000L,
) {
    private val lock = Any()
    private val cache = HitomiByteBudgetCache<Int, CachedManifest>(
        maxBytes = maxBytes,
        weigh = { _, entry -> entry.manifest.payloadBytes + HITOMI_MANIFEST_ENTRY_OVERHEAD_BYTES },
    )
    private val flights = SharedFlights<Int, HitomiGalleryManifest?>()

    init {
        require(ttlMillis > 0L) { "Hitomi gallery manifest TTL must be positive" }
        require(loadTimeoutMs > 0L)
    }

    suspend fun getOrLoad(
        galleryId: Int,
        loader: suspend () -> HitomiGalleryManifest?,
    ): JsonObject? {
        fresh(galleryId)?.let { return it.gallery }
        return flights.share(galleryId) {
            // A load that overlapped this caller's miss may have finished before this flight started.
            fresh(galleryId)?.let { return@share it }
            // The wrapper tells a missing gallery (null) apart from a load that ran out of time.
            val loaded = withTimeoutOrNull(loadTimeoutMs) { Result.success(loader()) }
                ?: throw SourceAdapterException(SourceFailureReason.NETWORK, "Hitomi gallery timed out")
            loaded.getOrThrow()?.also { manifest ->
                cache.put(galleryId, CachedManifest(manifest, nowMillis() + ttlMillis))
            }
        }?.gallery
    }

    /** The cached record for [galleryId] while it is fresh; an expired one is dropped. */
    private fun fresh(galleryId: Int): HitomiGalleryManifest? = synchronized(lock) {
        val cached = cache.get(galleryId) ?: return@synchronized null
        if (cached.expiresAtEpochMs > nowMillis()) return@synchronized cached.manifest
        cache.remove(galleryId)
        null
    }

    internal fun snapshot(): HitomiByteBudgetCacheSnapshot<Int> = cache.snapshot()

    private data class CachedManifest(
        val manifest: HitomiGalleryManifest,
        val expiresAtEpochMs: Long,
    )
}

private const val HITOMI_MANIFEST_ENTRY_OVERHEAD_BYTES = 128L
