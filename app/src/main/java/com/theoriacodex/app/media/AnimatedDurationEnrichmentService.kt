package com.theoriacodex.app.media

import com.theoriacodex.domain.adapter.DurationMetadataSourceResult
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import java.util.LinkedHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** Application-owned duration resolver shared by every feed route. */
internal class AnimatedDurationEnrichmentService(
    hasProviderDurationResolver: (Post) -> Boolean,
    resolveProviderDuration: suspend (Post) -> DurationMetadataSourceResult,
    probeDurationMs: suspend (Post) -> Long?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val successCacheSize: Int = DEFAULT_SUCCESS_CACHE_SIZE,
    private val negativeCacheSize: Int = ANIMATED_DURATION_ENRICHMENT_NEGATIVE_DECISION_LIMIT,
    private val negativeTtlMs: Long = ANIMATED_DURATION_ENRICHMENT_NEGATIVE_TTL_MS,
    maxConcurrentWork: Int = DEFAULT_MAX_CONCURRENT_WORK,
    operationTimeoutMs: Long = DEFAULT_DURATION_ACQUISITION_TIMEOUT_MS,
    traceRecorder: MediaDurationTraceRecorder = AndroidMediaDurationTraceRecorder,
    acquisitionEngineOverride: MediaDurationAcquisitionEngine? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : AnimatedDurationEnricher, AutoCloseable {
    constructor(
        acquisitionEngine: MediaDurationAcquisitionEngine,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    ) : this(
        hasProviderDurationResolver = { false },
        resolveProviderDuration = { DurationMetadataSourceResult.Unsupported },
        probeDurationMs = { null },
        acquisitionEngineOverride = acquisitionEngine,
        scope = scope,
    )

    private val acquisitionEngine = acquisitionEngineOverride ?: MediaDurationAcquisitionEngine(
        hasProviderDurationResolver = hasProviderDurationResolver,
        resolveProviderDuration = resolveProviderDuration,
        probeDuration = { post ->
            probeDurationMs(post)?.takeIf { durationMs -> durationMs > 0L }?.let { durationMs ->
                MediaDurationState.Known(durationMs, MediaDurationProvenance.CONTAINER_PROBE)
            } ?: MediaDurationState.Unsupported(
                MediaDurationUnsupportedReason.NO_AUTHORITATIVE_MEDIA,
            )
        },
        clock = clock,
        operationTimeoutMs = operationTimeoutMs,
        retryDelayMs = negativeTtlMs,
        traceRecorder = traceRecorder,
    )
    private val lock = Mutex()
    private val workPermits = Semaphore(maxConcurrentWork.coerceAtLeast(1))
    private val successCache = LinkedHashMap<PostId, Long>(16, 0.75f, true)
    private val negativeCache = LinkedHashMap<PostId, Long>(16, 0.75f, true)
    private val inFlight = mutableMapOf<PostId, SharedWork>()

    init {
        require(successCacheSize > 0) { "Success cache size must be positive" }
        require(negativeCacheSize > 0) { "Negative cache size must be positive" }
        require(negativeTtlMs > 0L) { "Negative cache TTL must be positive" }
    }

    override suspend fun enrich(post: Post): AnimatedDurationEnrichment? {
        animatedDurationMs(post)?.let { duration ->
            return AnimatedDurationEnrichment(post.id, duration)
        }
        return when (val acquisition = acquire(post)) {
            is Acquisition.Cached -> acquisition.result
            Acquisition.Negative -> null
            is Acquisition.Shared -> {
                acquisition.work.deferred.start()
                try {
                    acquisition.work.deferred.await()
                } finally {
                    release(post.id, acquisition.work)
                }
            }
        }
    }

    override fun close() {
        scope.cancel(CancellationException("Animated duration enrichment service closed"))
    }

    private suspend fun acquire(post: Post): Acquisition = lock.withLock {
        val cachedDuration = successCache[post.id]
        if (cachedDuration != null) {
            return@withLock Acquisition.Cached(AnimatedDurationEnrichment(post.id, cachedDuration))
        }
        val now = clock()
        val retryAt = negativeCache[post.id]
        if (retryAt != null && now < retryAt) return@withLock Acquisition.Negative
        if (retryAt != null) negativeCache.remove(post.id)

        val existing = inFlight[post.id]
        if (existing != null) {
            existing.waiters += 1
            return@withLock Acquisition.Shared(existing)
        }
        val work = SharedWork(
            deferred = scope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                compute(post)
            },
            waiters = 1,
        )
        inFlight[post.id] = work
        Acquisition.Shared(work)
    }

    private suspend fun release(postId: PostId, work: SharedWork) {
        lock.withLock {
            if (inFlight[postId] !== work) return@withLock
            work.waiters -= 1
            if (work.waiters <= 0) {
                inFlight.remove(postId)
                if (!work.deferred.isCompleted) {
                    work.deferred.cancel(CancellationException("No duration enrichment waiters remain"))
                }
            }
        }
    }

    private suspend fun compute(post: Post): AnimatedDurationEnrichment? {
        val result = workPermits.withPermit {
            when (val state = acquisitionEngine.acquire(post)) {
                is MediaDurationState.Known -> AnimatedDurationEnrichment(post.id, state.durationMs)
                MediaDurationState.Pending,
                is MediaDurationState.RetryableFailure,
                is MediaDurationState.Unsupported,
                -> null
            }
        }
        lock.withLock {
            if (result != null) {
                negativeCache.remove(post.id)
                putBounded(successCache, post.id, result.durationMs, successCacheSize)
            } else {
                putBounded(negativeCache, post.id, clock() + negativeTtlMs, negativeCacheSize)
            }
        }
        return result
    }

    private data class SharedWork(
        val deferred: Deferred<AnimatedDurationEnrichment?>,
        var waiters: Int,
    )

    private sealed interface Acquisition {
        data class Cached(val result: AnimatedDurationEnrichment) : Acquisition
        data object Negative : Acquisition
        data class Shared(val work: SharedWork) : Acquisition
    }

    private fun <Key, Value> putBounded(
        cache: LinkedHashMap<Key, Value>,
        key: Key,
        value: Value,
        limit: Int,
    ) {
        cache.remove(key)
        cache[key] = value
        while (cache.size > limit) {
            cache.remove(cache.keys.first())
        }
    }

    private companion object {
        const val DEFAULT_MAX_CONCURRENT_WORK = 1
        const val DEFAULT_SUCCESS_CACHE_SIZE = 128
    }
}
