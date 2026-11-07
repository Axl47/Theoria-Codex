package com.theoriacodex.app.viewer

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

// Probes and helpers for MediaBufferingBenchmarkTest.

/** Times one player's first frame, playback start, rebuffers and seek completions; attach and detach on main. */
internal class PlayerProbe(val player: ExoPlayer, private val surface: Surface) : Player.Listener {
    /** Elapsed-realtime clock value when the probe was made; server timelines are reported relative to it. */
    val attachedAt = SystemClock.elapsedRealtime()

    /**
     * Per seek: when it started (ms after [attachedAt]), then the player's load, render and state events in ms after
     * the seek request, until it was ready. Read on main.
     */
    val seekEvents = mutableListOf<MutableList<String>>()
    private var seekStartedAt = -1L
    private val seekLog = object : AnalyticsListener {
        override fun onLoadStarted(eventTime: EventTime, info: LoadEventInfo, data: MediaLoadData, retryCount: Int) =
            log(eventTime, "load@${info.dataSpec.position / KIB}k")

        override fun onLoadCanceled(eventTime: EventTime, info: LoadEventInfo, data: MediaLoadData) = log(eventTime, "cancel")

        override fun onLoadCompleted(eventTime: EventTime, info: LoadEventInfo, data: MediaLoadData) = log(eventTime, "done")

        override fun onRenderedFirstFrame(eventTime: EventTime, output: Any, renderTimeMs: Long) = log(eventTime, "frame")

        override fun onPlaybackStateChanged(eventTime: EventTime, state: Int) {
            log(eventTime, "state$state")
            if (state == Player.STATE_READY) seekStartedAt = -1L
        }
    }
    private val firstFrame = CompletableDeferred<Long>()
    private val playing = CompletableDeferred<Long>()
    private var seekReady: CompletableDeferred<Long>? = null
    private var readyOnce = false
    private var bufferingSince = -1L
    var rebufferCount = 0
        private set
    var rebufferMs = 0L
        private set

    /** Each stall as "<start>+<duration>", in ms after [attachedAt]. Read on main. */
    val stalls = mutableListOf<String>()

    fun attach() {
        player.addListener(this)
        player.addAnalyticsListener(seekLog)
        player.setVideoSurface(surface)
    }

    fun detach() {
        player.removeListener(this)
        player.removeAnalyticsListener(seekLog)
        player.clearVideoSurface(surface)
    }

    suspend fun awaitFirstFrame(): Long = awaitFirstFrameAt() - attachedAt

    suspend fun awaitPlaying(): Long = awaitPlayingAt() - attachedAt

    /** Elapsed-realtime clock value of the first rendered frame. */
    suspend fun awaitFirstFrameAt(): Long = withTimeout(PLAYER_TIMEOUT_MS) { firstFrame.await() }

    /** Elapsed-realtime clock value at which playback first started. */
    suspend fun awaitPlayingAt(): Long = withTimeout(PLAYER_TIMEOUT_MS) { playing.await() }

    /** Where the player was when its latest timed seek became ready. Read on main. */
    var seekReadyPositionMs = -1L
        private set

    /** Milliseconds from the seek request until the player is ready to play at [positionMs]. */
    suspend fun seekAndAwaitReady(positionMs: Long): Long =
        awaitReady(withContext(Dispatchers.Main) { startSeek { player.seekTo(positionMs) } })

    /** Runs [seek] as a timed seek, on main; [awaitReady] waits for it. */
    fun startSeek(seek: () -> Unit): TimedSeek {
        val ready = CompletableDeferred<Long>()
        seekReady = ready
        val now = SystemClock.elapsedRealtime()
        seekStartedAt = now
        seekEvents += mutableListOf("at${now - attachedAt}")
        seek()
        return TimedSeek(now, ready)
    }

    /** Milliseconds from [seek]'s request until the player was ready. */
    suspend fun awaitReady(seek: TimedSeek): Long = withTimeout(PLAYER_TIMEOUT_MS) { seek.ready.await() } - seek.startedAt

    private fun log(eventTime: EventTime, event: String) {
        if (seekStartedAt >= 0L) seekEvents.last() += "$event+${eventTime.realtimeMs - seekStartedAt}"
    }

    override fun onRenderedFirstFrame() {
        firstFrame.complete(SystemClock.elapsedRealtime())
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) playing.complete(SystemClock.elapsedRealtime())
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        val now = SystemClock.elapsedRealtime()
        when (playbackState) {
            Player.STATE_READY -> {
                if (bufferingSince >= 0L) {
                    rebufferMs += now - bufferingSince
                    stalls += "${bufferingSince - attachedAt}+${now - bufferingSince}"
                }
                bufferingSince = -1L
                readyOnce = true
                seekReady?.let { ready ->
                    seekReadyPositionMs = player.currentPosition
                    ready.complete(now)
                }
                seekReady = null
            }
            Player.STATE_BUFFERING -> if (readyOnce && seekReady == null && player.playWhenReady) {
                rebufferCount += 1
                bufferingSince = now
            }
            else -> Unit
        }
    }
}

/** A seek [PlayerProbe.startSeek] issued at [startedAt] (elapsed realtime); [ready] completes when it is ready. */
internal class TimedSeek(val startedAt: Long, val ready: CompletableDeferred<Long>)

/** A decoder output that drops every frame, standing in for the card's or Viewer's texture view. */
internal class BenchSurface : AutoCloseable {
    private val thread = HandlerThread("media-bench-surface").apply { start() }
    private val reader = ImageReader.newInstance(
        SURFACE_WIDTH, SURFACE_HEIGHT, ImageFormat.PRIVATE, SURFACE_IMAGES, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE,
    ).apply { setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(thread.looper)) }
    val surface: Surface get() = reader.surface

    override fun close() {
        reader.close()
        thread.quitSafely()
    }
}

/**
 * One timeline drag through the page's scrub previews, as the popup drives it: the finger rests on each target
 * until its frame is published. With [release], the finger then lifts on the last target: as in the timeline, the
 * release runs on main while the popup still follows the drag, which stops right after. Returns, per target, when
 * the finger moved there and when its frame arrived.
 */
internal suspend fun ViewerScrubPreviews.drag(
    targets: List<Long>,
    frameLog: PreviewFrameLog,
    release: ((Long) -> Unit)? = null,
): List<DragFrame> = coroutineScope {
    val positions = MutableStateFlow(targets.first())
    val published = Channel<Pair<Long, Long>>(Channel.UNLIMITED)
    var movedAt = SystemClock.elapsedRealtime()
    val following = launch(Dispatchers.Main) {
        follow(positions) { requested, bitmap ->
            frameLog.record(bitmap)
            published.trySend(requested to SystemClock.elapsedRealtime())
        }
    }
    val elapsed = targets.mapIndexed { index, target ->
        if (index > 0) {
            movedAt = SystemClock.elapsedRealtime()
            positions.value = target
        }
        withTimeout(PLAYER_TIMEOUT_MS) {
            var frame = published.receive()
            while (frame.first != target) frame = published.receive()
            DragFrame(movedAt, frame.second)
        }
    }
    release?.let { lift -> withContext(Dispatchers.Main) { lift(targets.last()) } }
    following.cancelAndJoin()
    elapsed
}

/** One drag target: when the finger moved there and when its preview frame was published. */
internal data class DragFrame(val movedAt: Long, val publishedAt: Long) {
    val elapsedMs: Long get() = publishedAt - movedAt
}

/**
 * Describes each published preview frame (its size, and "blank" when its pixels do not vary, as a failed read
 * would) and saves the latest to [file], the benchmark's generated media only, for a look at its orientation.
 * Record on main.
 */
internal class PreviewFrameLog(private val file: File) {
    val frames = mutableListOf<String>()
    private var latest: Bitmap? = null

    fun record(bitmap: Bitmap) {
        val samples = (0 until FRAME_SAMPLES).map { i -> bitmap.getPixel(bitmap.width * i / FRAME_SAMPLES, bitmap.height * i / FRAME_SAMPLES) }
        frames += "${bitmap.width}x${bitmap.height}" + if (samples.distinct().size == 1) " blank" else ""
        latest = bitmap
    }

    fun saveLatest() {
        latest?.let { bitmap -> file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) } }
    }
}

/** Samples Java plus native heap (where bitmaps live) until stopped. */
internal class MemorySampler(scope: CoroutineScope) {
    val baselineBytes = usedBytes()
    @Volatile private var peak = baselineBytes
    private val job = scope.launch {
        while (isActive) {
            peak = maxOf(peak, usedBytes())
            delay(MEMORY_SAMPLE_MS)
        }
    }

    fun stop(): MemoryPeak {
        job.cancel()
        return MemoryPeak(baselineBytes, maxOf(peak, usedBytes()))
    }

    private fun usedBytes(): Long = Debug.getNativeHeapAllocatedSize() +
        Runtime.getRuntime().let { runtime -> runtime.totalMemory() - runtime.freeMemory() }
}

internal data class MemoryPeak(val baselineBytes: Long, val peakBytes: Long)

internal fun median(values: List<Long>): Long = percentile(values, 50)

internal fun percentile(values: List<Long>, percentile: Int): Long {
    val sorted = values.sorted()
    return sorted[((sorted.size - 1) * percentile / 100.0).roundToInt()]
}

internal fun megabytes(bytes: Long): Double = (bytes / 10_485.76).roundToInt() / 100.0

internal const val PLAYER_TIMEOUT_MS = 30_000L
private const val KIB = 1024
private const val FRAME_SAMPLES = 8
private const val PNG_QUALITY = 100
private const val MEMORY_SAMPLE_MS = 50L
private const val SURFACE_WIDTH = 1920
private const val SURFACE_HEIGHT = 1080
private const val SURFACE_IMAGES = 4
