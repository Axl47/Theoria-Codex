package com.theoriacodex.sources.http

import java.io.IOException
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp only survives IOException on its dispatcher threads and rethrows anything else, which kills the app. Blocked
 * network access arrives as a SecurityException from DNS, so every client turns such failures into ordinary ones.
 */
val unexpectedFailuresAsIoExceptions: Interceptor = Interceptor { chain ->
    try {
        chain.proceed(chain.request())
    } catch (error: RuntimeException) {
        throw IOException("Network request failed unexpectedly", error)
    }
}

/**
 * Runs this call and hands the response to [read], which finishes on OkHttp's thread so a slow body never occupies a
 * coroutine dispatcher. Cancelling the caller cancels the call at once, which also unblocks a [read] in progress.
 */
suspend fun <T> Call.await(read: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resumeWith(runCatching { response.use(read) })
            }
        },
    )
}
