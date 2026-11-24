package com.theoriacodex.sources.http

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
