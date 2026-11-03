package com.theoriacodex.app.media

import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Spaces duration lookups per source and pauses a source that pushed back. Lookups share a host and
 * an IP with video playback, so tripping a host's limit would also break the videos on screen.
 */
internal class DurationRequestPacer(
    private val clock: () -> Long = System::currentTimeMillis,
    private val startIntervalMs: (SourceKey) -> Long = ::defaultDurationStartIntervalMs,
    private val cooldownMs: Long = DEFAULT_DURATION_COOLDOWN_MS,
) {
    private val lock = Mutex()
    private val nextStartBySource = mutableMapOf<SourceKey, Long>()

    /** Suspends until [source] may start another lookup, reserving that slot. */
    suspend fun awaitTurn(source: SourceKey) {
        val waitMs = lock.withLock {
            val now = clock()
            val start = maxOf(now, nextStartBySource[source] ?: now)
            nextStartBySource[source] = start + startIntervalMs(source)
            start - now
        }
        if (waitMs > 0L) delay(waitMs)
    }

    /** Holds every later lookup for [source] until the host has had time to recover. */
    suspend fun onThrottled(source: SourceKey) {
        lock.withLock {
            val resumeAt = clock() + cooldownMs
            nextStartBySource[source] = maxOf(nextStartBySource[source] ?: resumeAt, resumeAt)
        }
    }
}

private fun defaultDurationStartIntervalMs(source: SourceKey): Long {
    return when (source) {
        SourceKey.GELBOORU -> 500L
        SourceKey.PIXIV -> 500L
        else -> 0L
    }
}

private const val DEFAULT_DURATION_COOLDOWN_MS = 15_000L
