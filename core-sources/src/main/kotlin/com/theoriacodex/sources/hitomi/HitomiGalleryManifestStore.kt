package com.theoriacodex.sources.hitomi

import com.google.gson.JsonObject
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

internal data class HitomiGalleryManifest(
    val gallery: JsonObject,
    val payloadBytes: Long,
)

internal class HitomiGalleryManifestStore(
    maxBytes: Long,
    private val ttlMillis: Long,
    private val nowMillis: () -> Long,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val loadTimeoutMs: Long = 15_000L,
) {
    private val lock = Any()
    private val cache = HitomiByteBudgetCache<Int, CachedManifest>(
        maxBytes = maxBytes,
        weigh = { _, entry -> entry.manifest.payloadBytes + HITOMI_MANIFEST_ENTRY_OVERHEAD_BYTES },
    )
    private val flights = mutableMapOf<Int, SharedManifestLoad>()

    init {
        require(ttlMillis > 0L) { "Hitomi gallery manifest TTL must be positive" }
        require(loadTimeoutMs > 0L)
    }

    suspend fun getOrLoad(
        galleryId: Int,
        loader: suspend () -> HitomiGalleryManifest?,
    ): JsonObject? {
        val shared = synchronized(lock) {
            cache.get(galleryId)?.let { cached ->
                if (cached.expiresAtEpochMs > nowMillis()) return cached.manifest.gallery
                cache.remove(galleryId)
            }
            flights.getOrPut(galleryId) { createLoad(galleryId, loader) }.also { it.consumers++ }
        }
        shared.deferred.start()
        try {
            return shared.deferred.await().getOrThrow()?.gallery
        } finally {
            synchronized(lock) {
                shared.consumers--
                if (shared.consumers == 0) {
                    if (flights[galleryId] === shared) flights.remove(galleryId)
                    shared.deferred.cancel()
                }
            }
        }
    }

    private fun createLoad(galleryId: Int, loader: suspend () -> HitomiGalleryManifest?): SharedManifestLoad =
        SharedManifestLoad(scope.async(start = CoroutineStart.LAZY) {
            // Return failures as values so one provider failure cannot cancel the application owner.
            runCatchingPreservingCancellation {
                val result = withTimeoutOrNull(loadTimeoutMs) { Result.success(loader()) }
                    ?: throw SourceAdapterException(SourceFailureReason.NETWORK, "Hitomi gallery timed out")
                currentCoroutineContext().ensureActive()
                result.getOrThrow()?.also { manifest ->
                    cache.put(galleryId, CachedManifest(manifest, nowMillis() + ttlMillis))
                }
            }
        })

    internal fun snapshot(): HitomiByteBudgetCacheSnapshot<Int> = cache.snapshot()

    private data class CachedManifest(
        val manifest: HitomiGalleryManifest,
        val expiresAtEpochMs: Long,
    )

    private class SharedManifestLoad(
        val deferred: Deferred<Result<HitomiGalleryManifest?>>,
        var consumers: Int = 0,
    )
}

private const val HITOMI_MANIFEST_ENTRY_OVERHEAD_BYTES = 128L
