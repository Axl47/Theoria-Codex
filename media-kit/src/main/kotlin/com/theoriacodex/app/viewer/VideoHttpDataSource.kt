@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.theoriacodex.app.viewer

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol

private const val VIDEO_CONNECT_TIMEOUT_MS = 12_000L
private const val VIDEO_READ_TIMEOUT_MS = 24_000L
private const val VIDEO_MAX_REQUESTS = 64

fun videoHttpClient(shared: OkHttpClient = OkHttpClient()): OkHttpClient = shared.newBuilder()
    .protocols(listOf(Protocol.HTTP_1_1))
    .connectTimeout(VIDEO_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    .readTimeout(VIDEO_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    .dispatcher(Dispatcher().apply {
        maxRequests = VIDEO_MAX_REQUESTS
        maxRequestsPerHost = VIDEO_MAX_REQUESTS
    })
    .build()

class AbandoningHttpDataSourceFactory(private val client: Call.Factory) : DataSource.Factory {
    override fun createDataSource(): DataSource = AbandoningHttpDataSource(client)
}

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
