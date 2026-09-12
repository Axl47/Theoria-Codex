package com.theoriacodex.domain.coroutines

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Runs independent requests with a bounded fan-out and preserves input order in the result. */
suspend fun <T, R> Iterable<T>.mapConcurrent(
    concurrency: Int = 3,
    transform: suspend (T) -> R,
): List<R> = coroutineScope {
    val permits = Semaphore(concurrency)
    map { value -> async { permits.withPermit { transform(value) } } }.awaitAll()
}
