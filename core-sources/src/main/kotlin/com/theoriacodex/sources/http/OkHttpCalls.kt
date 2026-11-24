package com.theoriacodex.sources.http

import java.io.IOException
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Interceptor
import okhttp3.Response

val unexpectedFailuresAsIoExceptions: Interceptor = Interceptor { chain ->
    try {
        chain.proceed(chain.request())
    } catch (error: RuntimeException) {
        throw IOException("Network request failed unexpectedly", error)
    }
}

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
