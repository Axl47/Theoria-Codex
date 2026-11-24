@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.theoriacodex.app.viewer

import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToLong
import kotlinx.coroutines.Job
import okhttp3.Interceptor
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer

sealed interface FrameSample {
    class Frames(val frames: List<Bitmap>) : FrameSample

    data object Refused : FrameSample

    data object Unreachable : FrameSample

    data object Unreadable : FrameSample
}

class NetworkBudget(internal val maxBytes: Long) {
    private val spent = AtomicLong()

    internal val interceptor = Interceptor { chain ->
        if (spent.get() >= maxBytes) throw NetworkBudgetSpent()
        val response = chain.proceed(chain.request())
        val body = response.body
        val counted = object : ForwardingSource(body.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                val left = maxBytes - spent.get()
                if (left <= 0L) throw NetworkBudgetSpent()
                return super.read(sink, minOf(byteCount, left)).also { if (it > 0L) spent.addAndGet(it) }
            }
        }
        response.newBuilder().body(counted.buffer().asResponseBody(body.contentType(), body.contentLength())).build()
    }
}

internal class NetworkBudgetSpent : UnreadableMedia("The frame sampler's network budget is spent")

internal fun sampleVideoFrames(source: DataSource, uri: Uri, key: String?, count: Int, sizePx: Int, job: Job): FrameSample {
    val reader = DataSourceMediaReader(source, uri, key, job)
    val frames = try {
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(reader)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val params = MediaMetadataRetriever.BitmapParams().apply { preferredConfig = Bitmap.Config.ARGB_8888 }
            samplePointsUs(durationMs, count).mapNotNull { timeUs ->
                if (!job.isActive) return@mapNotNull null
                runCatching {
                    retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, sizePx, sizePx, params)
                }.getOrNull()
            }
        }
    } catch (_: RuntimeException) {
        emptyList()
    } finally {
        reader.close()
    }
    return frameSample(frames, reader.failure)
}

fun frameSample(frames: List<Bitmap>, failure: Throwable?): FrameSample {
    val causes = generateSequence(failure, Throwable::cause)
    val status = causes.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
    return when {
        failure == null || causes.any { it is UnreadableMedia || it.isFinalSourceFailure } ->
            if (frames.isEmpty()) FrameSample.Unreadable else FrameSample.Frames(frames)
        causes.any { it is MediaRefused } || status != null && refusesMedia(status) -> FrameSample.Refused
        else -> FrameSample.Unreachable
    }
}

private val Throwable.isFinalSourceFailure: Boolean
    get() = this is SourceAdapterException && reason in FINAL_SOURCE_FAILURES

private val FINAL_SOURCE_FAILURES = setOf(SourceFailureReason.PARSE, SourceFailureReason.UNKNOWN)

internal fun refusesMedia(status: Int): Boolean = status in CLIENT_ERRORS && status !in TRY_LATER

internal open class UnreadableMedia(message: String) : IOException(message)

internal class MediaRefused(message: String) : IOException(message)

internal fun samplePointsUs(durationMs: Long?, count: Int): List<Long> {
    if (durationMs == null || durationMs <= 0L || count <= 1) return listOf(0L)
    val first = durationMs * EDGE
    val step = durationMs * (1 - 2 * EDGE) / (count - 1)
    return List(count) { index -> ((first + step * index) * MICROS_PER_MS).roundToLong() }
}

internal class DataSourceMediaReader(
    private val source: DataSource,
    private val uri: Uri,
    private val key: String?,
    private val job: Job,
) : MediaDataSource() {
    @get:Synchronized
    var failure: IOException? = null
        private set
    private var length = C.LENGTH_UNSET.toLong()

    private val windows = LinkedHashMap<Long, ByteArray>(MAX_WINDOWS + 1, LOAD_FACTOR, true)

    init {
        synchronized(this) { guarded { fetch(0L) } }
    }

    @Synchronized
    override fun getSize(): Long = length

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        if (!job.isActive) return END
        if (length != C.LENGTH_UNSET.toLong() && position >= length) return END
        val start = windows.entries.firstOrNull { (start, bytes) -> position >= start && position < start + bytes.size }?.key
            ?: position
        val bytes = windows[start] ?: guarded { fetch(position) } ?: return END
        if (!job.isActive) return END
        val from = (position - start).toInt()
        val count = minOf(size, bytes.size - from)
        if (count <= 0) return END
        System.arraycopy(bytes, from, buffer, offset, count)
        return count
    }

    @Synchronized
    override fun close() {
        windows.clear()
    }

    private fun fetch(at: Long): ByteArray {
        val requested = if (length == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else minOf(length - at, WINDOW_BYTES)
        try {
            val opened = source.open(DataSpec.Builder().setUri(uri).setKey(key).setPosition(at).setLength(requested).build())
            if (length == C.LENGTH_UNSET.toLong() && opened != C.LENGTH_UNSET.toLong()) length = at + opened
            val wanted = if (opened == C.LENGTH_UNSET.toLong()) WINDOW_BYTES else minOf(opened, WINDOW_BYTES)
            val bytes = ByteArray(wanted.toInt())
            var filled = 0
            while (filled < bytes.size && job.isActive) {
                val read = source.read(bytes, filled, bytes.size - filled)
                if (read == C.RESULT_END_OF_INPUT) break
                filled += read
            }
            val window = if (filled == bytes.size) bytes else bytes.copyOf(filled)
            windows[at] = window
            if (windows.size > MAX_WINDOWS) windows.remove(windows.keys.first())
            return window
        } finally {
            runCatching { source.close() }
        }
    }

    private inline fun <T> guarded(block: () -> T): T? {
        if (failure != null || !job.isActive) return null
        return try {
            block()
        } catch (error: IOException) {
            failure = error
            null
        }
    }
}

private const val END = -1
private const val EDGE = 0.05
private const val MICROS_PER_MS = 1_000.0
private const val LOAD_FACTOR = 0.75f
private val CLIENT_ERRORS = 400..499

private val TRY_LATER = setOf(408, 429)

private const val WINDOW_BYTES = 256L * 1024L

private const val MAX_WINDOWS = 4
