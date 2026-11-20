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
        synchronized(lock) {
            cache.get(galleryId)?.let { cached ->
                if (cached.expiresAtEpochMs > nowMillis()) return cached.manifest.gallery
                cache.remove(galleryId)
            }
        }
        return flights.share(galleryId) {
            // The wrapper tells a missing gallery (null) apart from a load that ran out of time.
            val loaded = withTimeoutOrNull(loadTimeoutMs) { Result.success(loader()) }
                ?: throw SourceAdapterException(SourceFailureReason.NETWORK, "Hitomi gallery timed out")
            loaded.getOrThrow()?.also { manifest ->
                cache.put(galleryId, CachedManifest(manifest, nowMillis() + ttlMillis))
            }
        }?.gallery
    }

    internal fun snapshot(): HitomiByteBudgetCacheSnapshot<Int> = cache.snapshot()

    private data class CachedManifest(
        val manifest: HitomiGalleryManifest,
        val expiresAtEpochMs: Long,
    )
}

private const val HITOMI_MANIFEST_ENTRY_OVERHEAD_BYTES = 128L
