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
import androidx.media3.datasource.TransferListener
import java.io.IOException
import kotlinx.coroutines.Job
import kotlin.math.roundToLong

/** What sampling a video's frames gave. */
sealed interface VideoFrameSample {
    /** The frames that decoded, in time order; never empty. */
    class Frames(val frames: List<Bitmap>) : VideoFrameSample

    /** The server refused the file (a 4xx answer): its address may have expired. */
    data object Refused : VideoFrameSample

    /** The network failed; a later try may work. */
    data object Unreachable : VideoFrameSample

    /** No frame decodes from the file, or none did before the network budget ran out. */
    data object Unreadable : VideoFrameSample
}

/**
 * Up to [count] sync frames of the video [source] reads, spread evenly from 5% to 95% of its length, each scaled to fit
 * [sizePx]. `MediaMetadataRetriever` decodes only the keyframe at each point, and reads only the container's index and
 * those keyframes. Blocking: call it off the main thread. [job] ending stops the reads, so the retriever gives up soon.
 */
internal fun sampleVideoFrames(source: DataSource, uri: Uri, key: String?, count: Int, sizePx: Int, job: Job): VideoFrameSample {
    val reader = DataSourceMediaReader(source, uri, key, job)
    val frames = try {
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(reader)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val params = MediaMetadataRetriever.BitmapParams().apply { preferredConfig = Bitmap.Config.ARGB_8888 }
            samplePointsUs(durationMs, count).mapNotNull { timeUs ->
                if (!job.isActive) return@mapNotNull null
                retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, sizePx, sizePx, params)
            }
        }
    } catch (_: RuntimeException) {
        // setDataSource throws when the container cannot be read, including when its bytes could not be.
        emptyList()
    } finally {
        reader.close()
    }
    if (frames.isNotEmpty()) return VideoFrameSample.Frames(frames)
    return when (val failure = reader.failure) {
        is HttpDataSource.InvalidResponseCodeException ->
            if (failure.responseCode in CLIENT_ERRORS) VideoFrameSample.Refused else VideoFrameSample.Unreachable
        is NetworkBudgetSpent, null -> VideoFrameSample.Unreadable
        else -> VideoFrameSample.Unreachable
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
 * Wraps a Media3 upstream so it reads at most [maxBytes]; past that, every read fails with [NetworkBudgetSpent], so a
 * file whose index or keyframes lie far apart costs a bounded download.
 */
internal class BudgetedDataSource(private val upstream: DataSource, private val maxBytes: Long) : DataSource by upstream {
    private var read = 0L

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (read >= maxBytes) throw NetworkBudgetSpent()
        val count = upstream.read(buffer, offset, minOf(length.toLong(), maxBytes - read).toInt())
        if (count > 0) read += count
        return count
    }

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)
}

internal class NetworkBudgetSpent : IOException("The frame sampler's network budget is spent")

/**
 * Serves `MediaMetadataRetriever`'s random reads from a Media3 [DataSource]: a read where the last one ended continues
 * the open request, and any other read opens a request at most [WINDOW_BYTES] long there, so a jump costs one bounded
 * request. The first failure is kept in [failure] and fails every later read. The retriever calls in from its own
 * threads, one call at a time, so each call holds the reader's lock to see the last one's state.
 */
private class DataSourceMediaReader(
    private val source: DataSource,
    private val uri: Uri,
    private val key: String?,
    private val job: Job,
) : MediaDataSource() {
    @get:Synchronized
    var failure: IOException? = null
        private set
    private var length = C.LENGTH_UNSET.toLong()
    private var opened = false
    private var position = 0L
    private var windowEnd = 0L

    init {
        synchronized(this) { guarded { open(0L) } }
    }

    @Synchronized
    override fun getSize(): Long = length

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        if (length != C.LENGTH_UNSET.toLong() && position >= length) return END
        return guarded {
            if (!opened || position != this.position || position >= windowEnd) open(position)
            val count = source.read(buffer, offset, size)
            if (count == C.RESULT_END_OF_INPUT) END else count.also { this.position += it }
        } ?: END
    }

    @Synchronized
    override fun close() {
        if (opened) runCatching { source.close() }
        opened = false
    }

    /** The first request runs to the end of the file, so its answer tells the file's length; later ones are bounded. */
    private fun open(at: Long) {
        close()
        val toEnd = if (length == C.LENGTH_UNSET.toLong()) Long.MAX_VALUE else length - at
        val requested = if (at == 0L) C.LENGTH_UNSET.toLong() else minOf(toEnd, WINDOW_BYTES)
        val opened = source.open(DataSpec.Builder().setUri(uri).setKey(key).setPosition(at).setLength(requested).build())
        if (at == 0L) length = opened
        this.opened = true
        position = at
        windowEnd = if (opened == C.LENGTH_UNSET.toLong()) Long.MAX_VALUE else at + opened
    }

    private inline fun <T> guarded(block: () -> T): T? {
        if (failure != null || !job.isActive) return null
        return try {
            block()
        } catch (error: IOException) {
            failure = error
            close()
            null
        }
    }
}

private const val END = -1
private const val EDGE = 0.05
private const val MICROS_PER_MS = 1_000.0
private val CLIENT_ERRORS = 400..499

/** One jump's request; a keyframe of a light rendition usually fits. */
private const val WINDOW_BYTES = 512L * 1024L
