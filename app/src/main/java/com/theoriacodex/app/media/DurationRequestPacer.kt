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

    /** Suspends until [source] may start another lookup. Waking re-checks, so a new cooldown holds waiters too. */
    suspend fun awaitTurn(source: SourceKey) {
        while (true) {
            val waitMs = lock.withLock {
                val now = clock()
                val nextStart = nextStartBySource[source] ?: now
                if (now >= nextStart) nextStartBySource[source] = now + startIntervalMs(source)
                nextStart - now
            }
            if (waitMs <= 0L) return
            delay(waitMs)
        }
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
        // A user-added site's rate limits are unknown; pace it like Gelbooru.
        else -> if (source.isCustom) 500L else 0L
    }
}

private const val DEFAULT_DURATION_COOLDOWN_MS = 15_000L
