package com.theoriacodex.app.media

import com.theoriacodex.domain.adapter.DurationMetadataSourceAdapter
import com.theoriacodex.domain.adapter.DurationMetadataSourceResult
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
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
import kotlinx.coroutines.withTimeoutOrNull

/** Application-owned duration resolver shared by every feed route. */
internal class AnimatedDurationEnrichmentService(
    private val hasProviderDurationResolver: (Post) -> Boolean,
    private val resolveProviderDuration: suspend (Post) -> DurationMetadataSourceResult,
    private val probeDurationMs: suspend (Post) -> Long?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val successCacheSize: Int = DEFAULT_SUCCESS_CACHE_SIZE,
    private val negativeCacheSize: Int = ANIMATED_DURATION_ENRICHMENT_NEGATIVE_DECISION_LIMIT,
    private val negativeTtlMs: Long = ANIMATED_DURATION_ENRICHMENT_NEGATIVE_TTL_MS,
    maxConcurrentWork: Int = DEFAULT_MAX_CONCURRENT_WORK,
    private val operationTimeoutMs: Long = DEFAULT_OPERATION_TIMEOUT_MS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : AnimatedDurationEnricher, AutoCloseable {
    constructor(
        registry: SourceAdapterRegistry,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    ) : this(
        hasProviderDurationResolver = { post ->
            registry.adapterFor(post.id.source) is DurationMetadataSourceAdapter
        },
        resolveProviderDuration = { post ->
            val adapter = registry.adapterFor(post.id.source) as? DurationMetadataSourceAdapter
            adapter?.resolveDurationMetadata(post) ?: DurationMetadataSourceResult.Unsupported
        },
        probeDurationMs = ::probeRemoteVideoDurationMs,
        scope = scope,
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
        require(operationTimeoutMs > 0L) { "Duration acquisition timeout must be positive" }
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
            withTimeoutOrNull(operationTimeoutMs) {
                acquireDuration(post)?.takeIf { durationMs -> durationMs > 0L }?.let { durationMs ->
                    AnimatedDurationEnrichment(post.id, durationMs)
                }
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

    private suspend fun acquireDuration(post: Post): Long? {
        return when (
            planDurationAcquisition(
                DurationAcquisitionFacts(
                    knownDurationMs = animatedDurationMs(post),
                    persistedState = null,
                    hasAuthoritativeFullVideo = authoritativeDurationProbeRef(post) != null,
                    hasProviderDurationResolver = hasProviderDurationResolver(post),
                ),
            )
        ) {
            is DurationAcquisitionPlan.AlreadyKnown -> animatedDurationMs(post)
            is DurationAcquisitionPlan.UsePersisted -> null
            DurationAcquisitionPlan.ProbeAuthoritativeMedia -> probe(post)
            DurationAcquisitionPlan.AskProvider -> acquireFromProvider(post)
            is DurationAcquisitionPlan.Unsupported -> null
        }
    }

    private suspend fun acquireFromProvider(post: Post): Long? {
        return when (
            val providerResult = runCatchingPreservingCancellation {
                resolveProviderDuration(post)
            }.getOrNull() ?: return null
        ) {
            is DurationMetadataSourceResult.Known -> providerResult.durationMs
            is DurationMetadataSourceResult.AuthoritativeMedia -> {
                val probePost = post.copy(
                    full = providerResult.media,
                    media = listOf(providerResult.media),
                )
                if (authoritativeDurationProbeRef(probePost) == null) null else probe(probePost)
            }
            DurationMetadataSourceResult.Unsupported,
            DurationMetadataSourceResult.RetryableFailure,
            -> null
        }
    }

    private suspend fun probe(post: Post): Long? {
        return runCatchingPreservingCancellation { probeDurationMs(post) }
            .getOrNull()
            ?.takeIf { durationMs -> durationMs > 0L }
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
        const val DEFAULT_OPERATION_TIMEOUT_MS = 12_000L
    }
}
