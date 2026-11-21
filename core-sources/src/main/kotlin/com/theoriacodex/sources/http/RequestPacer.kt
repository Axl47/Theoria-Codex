package com.theoriacodex.sources.http

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Spaces one request lane at least [minIntervalMs] apart: callers take turns, and each waits out
 * whatever remains of the interval since the previous turn was granted. A source keeps one pacer per lane.
 */
internal class RequestPacer(
    private val minIntervalMs: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var lastTurnAtEpochMs = 0L

    suspend fun awaitTurn() = mutex.withLock {
        val waitMs = minIntervalMs - (clock() - lastTurnAtEpochMs)
        if (waitMs > 0L) delay(waitMs)
        lastTurnAtEpochMs = clock()
    }
}
