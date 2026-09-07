package com.theoriacodex.sources.http

import java.net.HttpURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Cancels the caller immediately and closes its connection off the caller's thread. Some JDK
 * implementations hold the response-read lock inside disconnect(), so awaiting that cleanup would
 * keep a departed route blocked. The blocking operation must configure finite connect/read timeouts.
 */
suspend fun <T> executeCancellableHttpConnection(
    connection: HttpURLConnection,
    block: () -> T,
): T = suspendCancellableCoroutine { continuation ->
    val request = connectionIoScope.launch {
        try {
            val result = runInterruptible {
                try {
                    block()
                } finally {
                    connection.disconnect()
                }
            }
            continuation.resumeWith(Result.success(result))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            continuation.resumeWith(Result.failure(failure))
        }
    }
    request.invokeOnCompletion { failure ->
        if (failure is CancellationException) continuation.cancel(failure)
    }
    continuation.invokeOnCancellation {
        request.cancel()
        connectionIoScope.launch { connection.disconnect() }
    }
}

private val connectionIoScope = CoroutineScope(Dispatchers.IO)
