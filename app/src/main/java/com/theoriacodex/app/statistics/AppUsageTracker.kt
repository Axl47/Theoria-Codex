package com.theoriacodex.app.statistics

import android.os.SystemClock
import com.theoriacodex.data.repository.StatisticsRepository
import com.theoriacodex.data.repository.UsageDurationDelta
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class StatisticsUsageCategory {
    BROWSING,
    WATCHING,
    CODEX,
}

/** Process-lifetime owner for monotonic foreground and mutually exclusive route-category timing. */
class AppUsageTracker internal constructor(
    private val repository: StatisticsRepository,
    private val scope: CoroutineScope,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    tickIntervalMs: Long = LIVE_USAGE_TICK_MS,
) {
    private val events = Channel<UsageEvent>(capacity = Channel.UNLIMITED)
    private val mutableLiveUsage = MutableStateFlow(UsageDurationDelta())
    val liveUsage: StateFlow<UsageDurationDelta> = mutableLiveUsage.asStateFlow()
    private val ownerJob: Job = scope.launch { consumeEvents() }
    private val tickerJob: Job? = tickIntervalMs.takeIf { it > 0L }?.let { interval ->
        scope.launch {
            while (isActive) {
                delay(interval)
                events.send(UsageEvent.Refresh)
            }
        }
    }

    fun onForeground() {
        events.trySend(UsageEvent.Foreground)
    }

    fun onBackground() {
        events.trySend(UsageEvent.Background)
    }

    fun onCategoryChanged(category: StatisticsUsageCategory?) {
        events.trySend(UsageEvent.CategoryChanged(category))
    }

    internal fun refreshLiveUsage() {
        events.trySend(UsageEvent.Refresh)
    }

    internal suspend fun awaitIdle() {
        val completion = CompletableDeferred<Unit>()
        events.send(UsageEvent.Barrier(completion))
        completion.await()
    }

    internal fun close() {
        tickerJob?.cancel()
        ownerJob.cancel()
        events.close()
    }

    private suspend fun consumeEvents() {
        var state = UsageTrackingState(intervalStartedAtMs = elapsedRealtime())
        for (event in events) {
            state = handleEvent(state, event)
        }
    }

    private suspend fun handleEvent(
        state: UsageTrackingState,
        event: UsageEvent,
    ): UsageTrackingState = when (event) {
        UsageEvent.Foreground -> handleForeground(state)
        UsageEvent.Background -> handleBackground(state)
        is UsageEvent.CategoryChanged -> handleCategoryChanged(state, event.category)
        UsageEvent.Refresh -> refreshLiveUsage(state)
        is UsageEvent.Barrier -> state.also { event.completion.complete(Unit) }
    }

    private suspend fun handleForeground(state: UsageTrackingState): UsageTrackingState {
        if (state.foreground) return state
        mutableLiveUsage.value = UsageDurationDelta()
        runCatchingPreservingCancellation { repository.recordAppOpen() }
        return state.copy(foreground = true, intervalStartedAtMs = elapsedRealtime())
    }

    private suspend fun handleBackground(state: UsageTrackingState): UsageTrackingState {
        if (!state.foreground) return state
        val nextStart = flushInterval(state.intervalStartedAtMs, state.category)
        mutableLiveUsage.value = UsageDurationDelta()
        return state.copy(foreground = false, intervalStartedAtMs = nextStart)
    }

    private suspend fun handleCategoryChanged(
        state: UsageTrackingState,
        category: StatisticsUsageCategory?,
    ): UsageTrackingState {
        if (state.category == category) return state
        val nextStart = if (state.foreground) {
            flushInterval(state.intervalStartedAtMs, state.category)
        } else {
            state.intervalStartedAtMs
        }
        if (state.foreground) mutableLiveUsage.value = UsageDurationDelta()
        return state.copy(category = category, intervalStartedAtMs = nextStart)
    }

    private fun refreshLiveUsage(state: UsageTrackingState): UsageTrackingState {
        mutableLiveUsage.value = if (state.foreground) {
            usageDelta(elapsedRealtime() - state.intervalStartedAtMs, state.category)
        } else {
            UsageDurationDelta()
        }
        return state
    }

    private suspend fun flushInterval(
        startedAtMs: Long,
        category: StatisticsUsageCategory?,
    ): Long {
        val now = elapsedRealtime()
        val delta = usageDelta(now - startedAtMs, category)
        if (delta.totalMs > 0L) {
            runCatchingPreservingCancellation { repository.addUsageDuration(delta) }
        }
        return now
    }
}

internal fun usageDelta(
    elapsedMs: Long,
    category: StatisticsUsageCategory?,
): UsageDurationDelta {
    val normalized = elapsedMs.coerceAtLeast(0L)
    return UsageDurationDelta(
        totalMs = normalized,
        browsingMs = normalized.takeIf { category == StatisticsUsageCategory.BROWSING } ?: 0L,
        watchingMs = normalized.takeIf { category == StatisticsUsageCategory.WATCHING } ?: 0L,
        codexMs = normalized.takeIf { category == StatisticsUsageCategory.CODEX } ?: 0L,
    )
}

private sealed interface UsageEvent {
    data object Foreground : UsageEvent
    data object Background : UsageEvent
    data class CategoryChanged(val category: StatisticsUsageCategory?) : UsageEvent
    data object Refresh : UsageEvent
    data class Barrier(val completion: CompletableDeferred<Unit>) : UsageEvent
}

private data class UsageTrackingState(
    val foreground: Boolean = false,
    val category: StatisticsUsageCategory? = null,
    val intervalStartedAtMs: Long,
)

private const val LIVE_USAGE_TICK_MS = 1_000L
