package com.theoriacodex.app.network

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.Source
import okio.buffer

/**
 * Continues an nhentai page that has gone quiet. Its page hosts sometimes stop sending partway through a file for
 * 15 to 30 seconds, about one page in forty when measured, while a new request on another connection answers in
 * about a second. So a page silent for [stallMs] carries on from its last byte, through [rescue]'s own connections.
 */
internal class StalledPageRescue(
    private val rescue: () -> OkHttpClient,
    private val stallMs: Int = DEFAULT_STALL_MS,
    private val appliesTo: (host: String) -> Boolean = PAGE_HOST::matches,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!appliesTo(request.url.host)) return chain.proceed(request)
        val response = try {
            chain.withReadTimeout(stallMs, TimeUnit.MILLISECONDS).proceed(request)
        } catch (_: SocketTimeoutException) {
            return rescue().newCall(request).execute()
        }
        if (response.code != HTTP_OK) return response
        val body = response.body
        val resuming = ResumingSource(body.source()) { offset ->
            if (chain.call().isCanceled()) throw IOException("Canceled")
            openFrom(request, offset)
        }
        return response.newBuilder().body(resuming.buffer().asResponseBody(body.contentType(), body.contentLength())).build()
    }

    private fun openFrom(request: Request, offset: Long): Source {
        val ranged = request.newBuilder().header("Range", "bytes=$offset-").header("Accept-Encoding", "identity").build()
        val response = rescue().newCall(ranged).execute()
        val source = response.body.source()
        when (response.code) {
            HTTP_PARTIAL -> Unit
            HTTP_OK -> source.skip(offset)
            else -> {
                response.close()
                throw IOException("Resuming a stalled page returned HTTP ${response.code}")
            }
        }
        return source
    }

    private companion object {
        const val DEFAULT_STALL_MS = 2_500
        const val HTTP_OK = 200
        const val HTTP_PARTIAL = 206
        val PAGE_HOST = Regex("""i\d*\.nhentai\.net""")
    }
}

/** Reads [current], and after one read timeout carries on from what [reopen] returns for the bytes already delivered. */
internal class ResumingSource(private var current: Source, private val reopen: (offset: Long) -> Source) : Source {
    private var offset = 0L
    private var resumed = false

    override fun read(sink: Buffer, byteCount: Long): Long {
        val count = try {
            current.read(sink, byteCount)
        } catch (stall: SocketTimeoutException) {
            if (resumed) throw stall
            resumed = true
            runCatching { current.close() }
            current = reopen(offset)
            current.read(sink, byteCount)
        }
        if (count > 0) offset += count
        return count
    }

    override fun timeout() = current.timeout()

    override fun close() = current.close()
}
