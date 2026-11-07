package com.theoriacodex.app.viewer

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
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

    /** Milliseconds from the seek request until the player is ready to play at [positionMs]. */
    suspend fun seekAndAwaitReady(positionMs: Long): Long {
        val ready = CompletableDeferred<Long>()
        val started = withContext(Dispatchers.Main) {
            seekReady = ready
            SystemClock.elapsedRealtime().also { now ->
                seekStartedAt = now
                seekEvents += mutableListOf("at${now - attachedAt}")
                player.seekTo(positionMs)
            }
        }
        return withTimeout(PLAYER_TIMEOUT_MS) { ready.await() } - started
    }

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
                if (bufferingSince >= 0L) rebufferMs += now - bufferingSince
                bufferingSince = -1L
                readyOnce = true
                seekReady?.complete(now)
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
private const val MEMORY_SAMPLE_MS = 50L
private const val SURFACE_WIDTH = 1920
private const val SURFACE_HEIGHT = 1080
private const val SURFACE_IMAGES = 4
