@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.theoriacodex.app.diagnostics

import android.net.TrafficStats
import android.os.Process
import androidx.media3.datasource.cache.CacheDataSource
import coil.EventListener
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.theoriacodex.mediakit.BuildConfig
import java.util.concurrent.atomic.AtomicLong
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.json.JSONObject

object NetworkUsage {
    val enabled: Boolean = BuildConfig.DEBUG

    enum class Stack { IMAGE, VIDEO, DOWNLOAD, SOURCE }

    private val requests = Stack.entries.associateWith { AtomicLong() }
    private val bytes = Stack.entries.associateWith { AtomicLong() }
    private val imageResults = coil.decode.DataSource.entries.associateWith { AtomicLong() }
    private val videoCachedBytes = AtomicLong()
    private val totalBaseline = AtomicLong()

    fun reset() {
        (requests.values + bytes.values + imageResults.values + videoCachedBytes).forEach { it.set(0) }
        totalBaseline.set(uidTotalBytes())
    }

    fun snapshot(): JSONObject {
        val stacks = JSONObject()
        Stack.entries.forEach { stack ->
            stacks.put(stack.name.lowercase(), JSONObject()
                .put("requests", requests.getValue(stack).get())
                .put("bytes", bytes.getValue(stack).get()))
        }
        val total = uidTotalBytes() - totalBaseline.get()
        return JSONObject()
            .put("totalBytes", total)
            .put("otherBytes", total - bytes.values.sumOf { it.get() })
            .put("stacks", stacks)
            .put("imageResultsBySource", JSONObject().apply {
                imageResults.forEach { (source, count) -> put(source.name.lowercase(), count.get()) }
            })
            .put("videoCachedBytesRead", videoCachedBytes.get())
    }

    private fun uidTotalBytes(): Long {
        val uid = Process.myUid()
        return TrafficStats.getUidRxBytes(uid).coerceAtLeast(0) + TrafficStats.getUidTxBytes(uid).coerceAtLeast(0)
    }

    fun counting(stack: Stack) = Interceptor { chain ->
        requests.getValue(stack).incrementAndGet()
        val response = chain.proceed(chain.request())
        response.newBuilder().body(CountingResponseBody(response.body, bytes.getValue(stack))).build()
    }

    private class CountingResponseBody(private val delegate: ResponseBody, total: AtomicLong) : ResponseBody() {
        private val counted = object : ForwardingSource(delegate.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long =
                super.read(sink, byteCount).also { read -> if (read > 0) total.addAndGet(read) }
        }.buffer()

        override fun contentType(): MediaType? = delegate.contentType()

        override fun contentLength(): Long = delegate.contentLength()

        override fun source(): BufferedSource = counted
    }

    fun countingImageResults(delegate: EventListener): EventListener =
        if (!enabled) delegate else object : EventListener by delegate {
            override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                delegate.onSuccess(request, result)
                imageResults.getValue(result.dataSource).incrementAndGet()
            }
        }

    val videoCacheListener: CacheDataSource.EventListener = object : CacheDataSource.EventListener {
        override fun onCachedBytesRead(cacheSizeBytes: Long, cachedBytesRead: Long) {
            videoCachedBytes.addAndGet(cachedBytesRead)
        }

        override fun onCacheIgnored(reason: Int) = Unit
    }
}
