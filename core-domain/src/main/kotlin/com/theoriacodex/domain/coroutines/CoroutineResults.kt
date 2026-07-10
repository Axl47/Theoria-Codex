package com.theoriacodex.domain.coroutines

import kotlinx.coroutines.CancellationException

/**
 * Mirrors [runCatching] for recoverable work while preserving coroutine cancellation as control
 * flow. Use this only at boundaries that may invoke suspend work; parsing and value conversion can
 * continue to use [runCatching].
 */
inline fun <T> runCatchingPreservingCancellation(block: () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }
}
