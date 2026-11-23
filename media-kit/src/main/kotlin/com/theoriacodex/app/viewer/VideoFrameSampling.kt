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
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToLong
import kotlinx.coroutines.Job
import okhttp3.Interceptor
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer

/** What sampling a video's or animation's frames gave. */
sealed interface FrameSample {
    /** The frames that decoded, in time order; never empty. */
    class Frames(val frames: List<Bitmap>) : FrameSample

    /** The server refused the file (a 4xx answer other than "try later"): its address may have expired. */
    data object Refused : FrameSample

    /** The network failed or the server asked to try later; a later try may work. */
    data object Unreachable : FrameSample

    /** No frame decodes from the file, or none did before the network budget ran out. */
    data object Unreadable : FrameSample
}

/**
 * The network bytes one post's frame sampling may download, counted as response bodies arrive (whatever reads them,
 * error bodies and skipped prefixes included) and shared by every request for that post, a refreshed address's too.
 * Past [maxBytes], reads fail with [NetworkBudgetSpent].
 */
class NetworkBudget(private val maxBytes: Long) {
    private val spent = AtomicLong()

    /** An OkHttp network interceptor that counts each response body against this budget. */
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

internal class NetworkBudgetSpent : IOException("The frame sampler's network budget is spent")

/**
 * Up to [count] sync frames of the video [source] reads, spread evenly from 5% to 95% of its length, each scaled to fit
 * [sizePx]. `MediaMetadataRetriever` decodes only the keyframe at each point, and reads only the container's index and
 * those keyframes. Blocking: call it off the main thread. [job] ending stops the reads, so the retriever gives up soon.
 */
internal fun sampleVideoFrames(source: DataSource, uri: Uri, key: String?, count: Int, sizePx: Int, job: Job): FrameSample {
    val reader = DataSourceMediaReader(source, uri, key, job)
    val frames = try {
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(reader)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val params = MediaMetadataRetriever.BitmapParams().apply { preferredConfig = Bitmap.Config.ARGB_8888 }
            samplePointsUs(durationMs, count).mapNotNull { timeUs ->
                if (!job.isActive) return@mapNotNull null
                // A point that fails to decode leaves the frames other points gave.
                runCatching {
                    retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, sizePx, sizePx, params)
                }.getOrNull()
            }
        }
    } catch (_: RuntimeException) {
        // setDataSource throws when the container cannot be read, including when its bytes could not be.
        emptyList()
    } finally {
        reader.close()
    }
    return frameSample(frames, reader.failure)
}

/**
 * What [frames] read before [failure] (null when every read succeeded) amount to. A network failure, or an answer to
 * try later, means a later run may read more, so even frames already read wait for it; a spent budget keeps what it
 * bought.
 */
internal fun frameSample(frames: List<Bitmap>, failure: IOException?): FrameSample {
    val causes = generateSequence<Throwable>(failure, Throwable::cause)
    val status = causes.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
    return when {
        failure == null || causes.any { it is NetworkBudgetSpent } ->
            if (frames.isEmpty()) FrameSample.Unreadable else FrameSample.Frames(frames)
        status != null && status in CLIENT_ERRORS && status !in TRY_LATER -> FrameSample.Refused
        else -> FrameSample.Unreachable
    }
}

/** [count] points evenly from 5% to 95% of [durationMs], in microseconds; only the first frame when the length is unknown. */
internal fun samplePointsUs(durationMs: Long?, count: Int): List<Long> {
    if (durationMs == null || durationMs <= 0L || count <= 1) return listOf(0L)
    val first = durationMs * EDGE
    val step = durationMs * (1 - 2 * EDGE) / (count - 1)
    return List(count) { index -> ((first + step * index) * MICROS_PER_MS).roundToLong() }
}

/**
 * Serves `MediaMetadataRetriever`'s random reads from a Media3 [DataSource] in windows of at most [WINDOW_BYTES], each
 * fetched by one request and kept in memory, the last [MAX_WINDOWS] of them: the retriever reads the container's index
 * again for every frame, so those reads cost nothing after the first. The first request asks for the whole file, so its
 * answer tells the file's length, but reads only its first window. The first failure is kept in [failure] and fails
 * every later read. The retriever calls in from its own threads, one call at a time, so each call holds the reader's
 * lock to see the last one's state.
 */
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

    /** Fetched windows by their first byte's position, least recently read first. */
    private val windows = LinkedHashMap<Long, ByteArray>(MAX_WINDOWS + 1, LOAD_FACTOR, true)

    init {
        synchronized(this) { guarded { fetch(0L) } }
    }

    @Synchronized
    override fun getSize(): Long = length

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        // Kept windows too: once the job ends, the retriever gets nothing more to decode.
        if (!job.isActive) return END
        if (length != C.LENGTH_UNSET.toLong() && position >= length) return END
        // Entries, not get(), while searching: a get() reorders the map mid-iteration.
        val start = windows.entries.firstOrNull { (start, bytes) -> position >= start && position < start + bytes.size }?.key
            ?: position
        // Reading a kept window marks it recently read.
        val bytes = windows[start] ?: guarded { fetch(position) } ?: return END
        // A fetch can outlast the job; what it brought is kept but not handed over.
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

    /** Fetches the window starting [at], keeps it, and returns its bytes (fewer at the end of the file). */
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

/** Request Timeout and Too Many Requests: the file is there, the server only wants a later try. */
private val TRY_LATER = setOf(408, 429)

/** One request's bytes: a light rendition's index or keyframe usually fits. */
private const val WINDOW_BYTES = 256L * 1024L

/** Windows kept: the index (once at each end of a file whose index trails it) and a few keyframes. */
private const val MAX_WINDOWS = 4
