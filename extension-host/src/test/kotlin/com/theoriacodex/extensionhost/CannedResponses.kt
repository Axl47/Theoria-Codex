package com.theoriacodex.extensionhost

import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** A response to [request] without any network, for application interceptors that end a chain. */
fun cannedResponse(request: Request, body: String = "", code: Int = 200): Response = Response.Builder()
    .request(request)
    .protocol(Protocol.HTTP_1_1)
    .code(code)
    .message("canned")
    .body(body.toResponseBody())
    .build()

/** Ends a chain by answering every request with [reply]. */
fun answering(reply: (Request) -> Response): Interceptor = Interceptor { chain -> reply(chain.request()) }

/** Runs [block], which may suspend, and returns the [T] it throws; any other outcome fails the test. */
inline fun <reified T : Throwable> assertFailsWith(block: () -> Unit): T {
    try {
        block()
    } catch (failure: Throwable) {
        if (failure is T) return failure
        throw failure
    }
    throw AssertionError("Expected ${T::class.java.simpleName}, but nothing was thrown")
}
