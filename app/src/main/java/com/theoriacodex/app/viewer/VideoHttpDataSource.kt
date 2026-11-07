@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.theoriacodex.app.viewer

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.theoriacodex.app.customsites.BrowserVerifiedSites
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol

private const val VIDEO_CONNECT_TIMEOUT_MS = 12_000L
private const val VIDEO_READ_TIMEOUT_MS = 24_000L
private const val VIDEO_MAX_REQUESTS = 64

/**
 * The HTTP client behind every video player, prefetch and scrub preview. It matches the `HttpURLConnection` stack
 * it replaced: HTTP/1.1 only (as Android's `HttpURLConnection` speaks; HTTP/2's 16 MiB stream window would let a
 * paused player buffer far more than a TCP window), the same timeouts, browser-verified sites' cookies, and no
 * per-host request cap. OkHttp queues calls above its dispatcher limits, where `HttpURLConnection` had none.
 *
 * The agent comes from the stream's headers, its source's media headers ([com.theoriacodex.app.source.requestHeaders])
 * as its images use, so a custom site's own agent, which its clearance cookies are usually bound to, applies to video.
 */
internal fun videoHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .protocols(listOf(Protocol.HTTP_1_1))
    .connectTimeout(VIDEO_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    .readTimeout(VIDEO_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    .dispatcher(Dispatcher().apply {
        maxRequests = VIDEO_MAX_REQUESTS
        maxRequestsPerHost = VIDEO_MAX_REQUESTS
    })
    .cookieJar(BrowserVerifiedSites.cookieJar)
    .build()

/** HTTP data sources on [client] whose half-read responses are dropped, not drained ([AbandoningHttpDataSource]). */
internal class AbandoningHttpDataSourceFactory(private val client: Call.Factory) : DataSource.Factory {
    override fun createDataSource(): DataSource = AbandoningHttpDataSource(client)
}

/**
 * Closes a response it has not read to the end by cancelling its call. Both `HttpURLConnection` and OkHttp otherwise
 * read the rest of a half-read HTTP/1.1 response for up to 100 ms to keep its connection, and every seek, scrub
 * preview move and stopped player closes one part-way, on the path to its next request. Over a response that long
 * the drain ends by timing out, which drops the connection anyway. Cancelling a call that already finished is a
 * no-op, so fully read responses keep their connection.
 */
private class AbandoningHttpDataSource(client: Call.Factory) : DataSource {
    private var call: Call? = null
    private val upstream = OkHttpDataSource.Factory { request -> client.newCall(request).also { call = it } }
        .createDataSource()

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long = upstream.open(dataSpec)

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        call?.cancel()
        call = null
        upstream.close()
    }
}
