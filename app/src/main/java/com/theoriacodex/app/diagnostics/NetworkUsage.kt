@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.theoriacodex.app.diagnostics

import android.net.TrafficStats
import android.os.Process
import androidx.media3.datasource.cache.CacheDataSource
import coil.EventListener
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.theoriacodex.app.BuildConfig
import java.util.concurrent.atomic.AtomicLong
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.json.JSONObject

/**
 * Counts what the app downloads through each of its HTTP clients, for the opt-in live data-usage session
 * (`scripts/real_data_device.sh data-usage`). Only debug builds count: [enabled] is a build constant, so release
 * builds install no hooks and R8 removes this object. The snapshot also reports the app's total traffic, so
 * whatever no client reads (headers, TLS, data still in flight when a transfer is cancelled) shows as the remainder.
 */
internal object NetworkUsage {
    val enabled: Boolean = BuildConfig.DEBUG

    /**
     * One per client in `AppHttpClients`. IMAGE: Coil. VIDEO: Media3 playback and prefetch. DOWNLOAD: Viewer GIFs,
     * Pixiv animations and offline saves. SOURCE: provider APIs, indexes and video-length probes.
     */
    enum class Stack { IMAGE, VIDEO, DOWNLOAD, SOURCE }

    private val requests = Stack.entries.associateWith { AtomicLong() }
    private val bytes = Stack.entries.associateWith { AtomicLong() }
    private val imageResults = coil.decode.DataSource.entries.associateWith { AtomicLong() }
    private val videoCachedBytes = AtomicLong()
    private val totalBaseline = AtomicLong()

    /** Starts a new measurement window; the app's total traffic is measured from here. */
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

    /** Received plus sent bytes on every network, including TLS and headers: what a data plan is billed. */
    private fun uidTotalBytes(): Long {
        val uid = Process.myUid()
        return TrafficStats.getUidRxBytes(uid).coerceAtLeast(0) + TrafficStats.getUidTxBytes(uid).coerceAtLeast(0)
    }

    /**
     * A network interceptor counting every request a client sends, redirects and retries included. Bodies count as
     * they are read and before gzip decoding, so a download cancelled midway (an image scrolled past) counts what the
     * app read of it; bytes already in flight at the cancel arrive anyway and land in the remainder.
     */
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

    /** Wraps Coil's event listener to also count where each displayed image came from (memory, disk, network). */
    fun countingImageResults(delegate: EventListener): EventListener =
        if (!enabled) delegate else object : EventListener by delegate {
            override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                delegate.onSuccess(request, result)
                imageResults.getValue(result.dataSource).incrementAndGet()
            }
        }

    /** Installed on the video cache: bytes played from the device instead of downloaded again. */
    val videoCacheListener: CacheDataSource.EventListener = object : CacheDataSource.EventListener {
        override fun onCachedBytesRead(cacheSizeBytes: Long, cachedBytesRead: Long) {
            videoCachedBytes.addAndGet(cachedBytesRead)
        }

        override fun onCacheIgnored(reason: Int) = Unit
    }
}
