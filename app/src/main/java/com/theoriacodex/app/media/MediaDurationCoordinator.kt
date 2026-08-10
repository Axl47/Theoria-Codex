package com.theoriacodex.app.media

import android.os.Build
import android.os.Trace
import com.theoriacodex.data.repository.MediaDurationRepository
import com.theoriacodex.domain.model.Post
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun interface MediaDurationAcquirer {
    suspend fun acquire(post: Post): MediaDurationState
}

interface MediaDurationTraceRecorder {
    fun demand()

    fun providerResolve()

    fun probe()

    fun workloadStarted(cookie: Int)

    fun workloadFinished(cookie: Int)

    fun publication()

    fun settled()
}

object AndroidMediaDurationTraceRecorder : MediaDurationTraceRecorder {
    override fun demand() = recordEvent(TRACE_DURATION_DEMAND)

    override fun providerResolve() = recordEvent(TRACE_DURATION_RESOLVE)

    override fun probe() = recordEvent(TRACE_DURATION_PROBE)

    override fun workloadStarted(cookie: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Trace.beginAsyncSection(TRACE_DURATION_WORKLOAD, cookie)
        }
    }

    override fun workloadFinished(cookie: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Trace.endAsyncSection(TRACE_DURATION_WORKLOAD, cookie)
        }
    }

    override fun publication() = recordEvent(TRACE_DURATION_PUBLISH)

    override fun settled() = recordEvent(TRACE_DURATION_SETTLED)

    private fun recordEvent(name: String) {
        Trace.beginSection(name)
        Trace.endSection()
    }
}

object NoOpMediaDurationTraceRecorder : MediaDurationTraceRecorder {
    override fun demand() = Unit

    override fun providerResolve() = Unit

    override fun probe() = Unit

    override fun workloadStarted(cookie: Int) = Unit

    override fun workloadFinished(cookie: Int) = Unit

    override fun publication() = Unit

    override fun settled() = Unit
}

/**
 * Application-scoped owner for duration demand, cancellation, single-flight work, and publication.
 * Routes never drain acquisition loops and metadata publication never becomes a new demand input.
 */
class MediaDurationCoordinator(
    private val acquirer: MediaDurationAcquirer,
    private val durationRepository: MediaDurationRepository? = null,
    parentScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val clock: () -> Long = System::currentTimeMillis,
    private val retryDelayMs: Long = DEFAULT_RETRY_DELAY_MS,
    maxQueuedKeys: Int = DEFAULT_MAX_QUEUED_KEYS,
    private val maxConcurrentWork: Int = DEFAULT_MAX_CONCURRENT_WORK,
    private val maxRetainedStates: Int = DEFAULT_MAX_RETAINED_STATES,
    private val traceRecorder: MediaDurationTraceRecorder = AndroidMediaDurationTraceRecorder,
) : AutoCloseable {
    private val coordinatorJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + coordinatorJob)
    private val lock = Mutex()
    private val scheduler = DurationDemandScheduler(maxQueuedKeys)
    private val posts = mutableMapOf<MediaDurationKey, Post>()
    private val active = mutableMapOf<MediaDurationKey, ActiveWork>()
    private val retainedStates = LinkedHashMap<MediaDurationKey, MediaDurationState>(16, 0.75f, true)
    private val mutableStates = MutableStateFlow<Map<MediaDurationKey, MediaDurationState>>(emptyMap())
    private val traceCookie = AtomicInteger(0)
    private var gate = DurationExecutionGate(lifecycleStarted = false, scrollIdle = true)
    private var hadOutstandingWork = false

    val states: StateFlow<Map<MediaDurationKey, MediaDurationState>> = mutableStates.asStateFlow()

    init {
        require(retryDelayMs > 0L) { "Retry delay must be positive" }
        require(maxConcurrentWork > 0) { "Duration concurrency must be positive" }
        require(maxRetainedStates > 0) { "Retained duration-state bound must be positive" }
    }

    suspend fun submit(post: Post, demand: DurationDemand): Boolean {
        require(demand.key.postId == post.id) { "Duration demand key must match its post" }
        traceRecorder.demand()

        post.durationMs?.takeIf { durationMs -> durationMs > 0L }?.let { durationMs ->
            publishKnown(
                key = demand.key,
                durationMs = durationMs,
                provenance = MediaDurationProvenance.PROVIDER,
            )
            return true
        }

        val shouldLoadStoredState = lock.withLock {
            demand.key !in retainedStates &&
                demand.key !in active &&
                !scheduler.contains(demand.key)
        }
        val storedState = if (shouldLoadStoredState) loadStoredState(demand.key) else null
        return lock.withLock { submitLocked(post, demand, storedState) }
    }

    private fun submitLocked(
        post: Post,
        demand: DurationDemand,
        storedState: MediaDurationState?,
    ): Boolean {
        when (val current = retainedStates[demand.key]) {
            is MediaDurationState.Known,
            is MediaDurationState.Unsupported,
            -> return false

            is MediaDurationState.RetryableFailure -> {
                if (clock() < current.retryAtEpochMs) return false
                removeStateLocked(demand.key)
            }

            MediaDurationState.Pending,
            null,
            -> Unit
        }

        active[demand.key]?.let { work ->
            work.demands[demand.identity] = demand
            work.priority = work.demands.values.minOf(DurationDemand::priority)
            return true
        }

        if (storedState != null) {
            putStateLocked(demand.key, storedState)
            traceRecorder.publication()
            return false
        }

        posts[demand.key] = post
        val submission = scheduler.submit(demand)
        if (submission == DurationDemandSubmission.Rejected) {
            if (!scheduler.contains(demand.key)) posts.remove(demand.key)
            return false
        }
        val accepted = submission as DurationDemandSubmission.Accepted
        accepted.evictedKey?.let(::discardEvictedKeyLocked)
        putStateLocked(demand.key, MediaDurationState.Pending)
        hadOutstandingWork = true
        preemptBackgroundForLocked(demand.priority)
        pumpLocked()
        return true
    }

    suspend fun releaseIdentity(identity: String) = lock.withLock {
        require(identity.isNotBlank()) { "Duration demand identity must not be blank" }
        val queuedAffected = scheduler.removeIdentity(identity)
        queuedAffected.forEach(::discardUnownedPendingKeyLocked)

        active.values.toList().forEach { work ->
            if (work.demands.remove(identity) == null) return@forEach
            if (work.demands.isEmpty()) {
                cancelActiveLocked(work, requeue = false)
                discardUnownedPendingKeyLocked(work.key)
            } else {
                work.priority = work.demands.values.minOf(DurationDemand::priority)
            }
        }
        pumpLocked()
        recordSettledIfNeededLocked()
    }

    suspend fun updateEnvironment(lifecycleStarted: Boolean, scrollIdle: Boolean) = lock.withLock {
        gate = DurationExecutionGate(
            lifecycleStarted = lifecycleStarted,
            scrollIdle = scrollIdle,
        )
        active.values.toList().forEach { work ->
            val mustPause = !lifecycleStarted ||
                (!scrollIdle && work.priority == DurationDemandPriority.BACKGROUND_IDLE)
            if (mustPause) cancelActiveLocked(work, requeue = true)
        }
        pumpLocked()
    }

    suspend fun publishKnown(
        key: MediaDurationKey,
        durationMs: Long,
        provenance: MediaDurationProvenance,
    ) {
        require(durationMs > 0L) { "Published duration must be positive" }
        val state = MediaDurationState.Known(durationMs, provenance)
        lock.withLock { publishKnownLocked(key, state) }
        persistState(key, state)
    }

    override fun close() {
        coordinatorJob.cancel(CancellationException("Media duration coordinator closed"))
    }

    private fun publishKnownLocked(
        key: MediaDurationKey,
        state: MediaDurationState.Known,
    ) {
        scheduler.removeKey(key)
        active[key]?.let { work -> cancelActiveLocked(work, requeue = false) }
        posts.remove(key)
        putStateLocked(key, state)
        traceRecorder.publication()
        pumpLocked()
        recordSettledIfNeededLocked()
    }

    private fun preemptBackgroundForLocked(priority: DurationDemandPriority) {
        if (priority > DurationDemandPriority.VISIBLE || active.size < maxConcurrentWork) return
        val background = active.values
            .filter { work -> work.priority == DurationDemandPriority.BACKGROUND_IDLE }
            .minByOrNull(ActiveWork::sequence)
            ?: return
        cancelActiveLocked(background, requeue = true)
    }

    private fun pumpLocked() {
        while (active.size < maxConcurrentWork) {
            val next = scheduler.takeNext(gate) ?: break
            val post = posts[next.key]
            if (post == null) {
                removeStateIfPendingLocked(next.key)
                continue
            }
            startWorkLocked(post, next)
        }
        recordSettledIfNeededLocked()
    }

    private fun startWorkLocked(post: Post, scheduled: ScheduledDurationWork) {
        val cookie = traceCookie.updateAndGet { current ->
            if (current == Int.MAX_VALUE) 1 else current + 1
        }
        val sequence = cookie.toLong()
        lateinit var work: ActiveWork
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val result = try {
                acquirer.acquire(post)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                MediaDurationState.RetryableFailure(
                    retryAtEpochMs = clock() + retryDelayMs,
                    reason = MediaDurationFailureReason.TRANSPORT_FAILURE,
                )
            }
            completeWork(work, normalizeResult(result))
        }
        work = ActiveWork(
            key = scheduled.key,
            priority = scheduled.priority,
            demands = scheduled.demands.associateByTo(linkedMapOf(), DurationDemand::identity),
            sequence = sequence,
            traceCookie = cookie,
            job = job,
        )
        active[scheduled.key] = work
        traceRecorder.workloadStarted(cookie)
        job.start()
    }

    private suspend fun completeWork(work: ActiveWork, result: MediaDurationState) {
        val completedState = lock.withLock {
            if (active[work.key] !== work) return@withLock null
            active.remove(work.key)
            traceRecorder.workloadFinished(work.traceCookie)
            posts.remove(work.key)
            putStateLocked(work.key, result)
            traceRecorder.publication()
            pumpLocked()
            result
        }
        completedState?.let { state -> persistState(work.key, state) }
    }

    private fun normalizeResult(result: MediaDurationState): MediaDurationState {
        return if (result == MediaDurationState.Pending) {
            MediaDurationState.RetryableFailure(
                retryAtEpochMs = clock() + retryDelayMs,
                reason = MediaDurationFailureReason.PROVIDER_FAILURE,
            )
        } else {
            result
        }
    }

    private suspend fun loadStoredState(key: MediaDurationKey): MediaDurationState? {
        val repository = durationRepository ?: return null
        return try {
            repository.get(key.toStoredKey())?.toMediaDurationState()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun persistState(key: MediaDurationKey, state: MediaDurationState) {
        val repository = durationRepository ?: return
        val storedState = state.toStoredState() ?: return
        try {
            repository.put(key.toStoredKey(), storedState)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Duration persistence is a cache optimization and never changes a successful outcome.
        }
    }

    private fun cancelActiveLocked(work: ActiveWork, requeue: Boolean) {
        if (active.remove(work.key) !== work) return
        traceRecorder.workloadFinished(work.traceCookie)
        if (requeue) {
            var retained = false
            work.demands.values.forEach { demand ->
                when (val submission = scheduler.submit(demand)) {
                    is DurationDemandSubmission.Accepted -> {
                        retained = true
                        submission.evictedKey?.let(::discardEvictedKeyLocked)
                    }
                    DurationDemandSubmission.Rejected -> Unit
                }
            }
            if (!retained) discardUnownedPendingKeyLocked(work.key)
        }
        work.job.cancel(CancellationException("Duration demand no longer executable"))
    }

    private fun discardEvictedKeyLocked(key: MediaDurationKey) {
        posts.remove(key)
        removeStateIfPendingLocked(key)
    }

    private fun discardUnownedPendingKeyLocked(key: MediaDurationKey) {
        if (key !in active && !scheduler.contains(key)) {
            posts.remove(key)
            removeStateIfPendingLocked(key)
        }
    }

    private fun putStateLocked(key: MediaDurationKey, state: MediaDurationState) {
        retainedStates[key] = state
        pruneStatesLocked()
        mutableStates.value = retainedStates.toMap()
    }

    private fun removeStateLocked(key: MediaDurationKey) {
        if (retainedStates.remove(key) != null) mutableStates.value = retainedStates.toMap()
    }

    private fun removeStateIfPendingLocked(key: MediaDurationKey) {
        if (retainedStates[key] == MediaDurationState.Pending) removeStateLocked(key)
    }

    private fun pruneStatesLocked() {
        if (retainedStates.size <= maxRetainedStates) return
        val iterator = retainedStates.entries.iterator()
        while (retainedStates.size > maxRetainedStates && iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value != MediaDurationState.Pending) iterator.remove()
        }
    }

    private fun recordSettledIfNeededLocked() {
        if (hadOutstandingWork && active.isEmpty() && scheduler.queuedKeyCount() == 0) {
            hadOutstandingWork = false
            traceRecorder.settled()
        }
    }

    private data class ActiveWork(
        val key: MediaDurationKey,
        var priority: DurationDemandPriority,
        val demands: LinkedHashMap<String, DurationDemand>,
        val sequence: Long,
        val traceCookie: Int,
        val job: Job,
    )

    private companion object {
        const val DEFAULT_RETRY_DELAY_MS = 5L * 60L * 1_000L
        const val DEFAULT_MAX_QUEUED_KEYS = 512
        const val DEFAULT_MAX_CONCURRENT_WORK = 1
        const val DEFAULT_MAX_RETAINED_STATES = 2_048
    }
}

internal const val TRACE_DURATION_DEMAND = "TheoriaDurationDemand"
internal const val TRACE_DURATION_RESOLVE = "TheoriaDurationResolve"
internal const val TRACE_DURATION_PROBE = "TheoriaDurationProbe"
internal const val TRACE_DURATION_PUBLISH = "TheoriaDurationPublish"
internal const val TRACE_DURATION_SETTLED = "TheoriaDurationSettled"
internal const val TRACE_DURATION_WORKLOAD = "TheoriaDurationWorkload"
