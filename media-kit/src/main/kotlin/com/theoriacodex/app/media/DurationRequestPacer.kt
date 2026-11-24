package com.theoriacodex.app.media

import com.theoriacodex.app.source.DurationLookupPolicy
import com.theoriacodex.app.source.presentation
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DurationRequestPacer(
    private val clock: () -> Long = System::currentTimeMillis,
    private val startIntervalMs: (SourceKey) -> Long = { source -> source.presentation().durationLookup.startIntervalMs },
    private val cooldownMs: Long = DEFAULT_DURATION_COOLDOWN_MS,
) {
    private val lock = Mutex()
    private val nextStartBySource = mutableMapOf<SourceKey, Long>()

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

    suspend fun onThrottled(source: SourceKey) {
        lock.withLock {
            val resumeAt = clock() + cooldownMs
            nextStartBySource[source] = maxOf(nextStartBySource[source] ?: resumeAt, resumeAt)
        }
    }
}

private const val DEFAULT_DURATION_COOLDOWN_MS = 15_000L
