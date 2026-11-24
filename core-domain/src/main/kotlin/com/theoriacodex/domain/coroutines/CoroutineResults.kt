package com.theoriacodex.domain.coroutines

import kotlinx.coroutines.CancellationException

inline fun <T> runCatchingPreservingCancellation(block: () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }
}
