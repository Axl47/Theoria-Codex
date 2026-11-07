package com.theoriacodex.app.viewer

import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.lifecycle.SavedStateHandle
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil.imageLoader
import coil.request.SuccessResult
import coil.size.Precision
import coil.size.Scale
import com.theoriacodex.app.media.viewerMediaDeliveryPlan
import com.theoriacodex.app.search.FEED_PLAYER_RELEASE_GRACE_MS
import com.theoriacodex.app.source.requestHeaders
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.model.VideoQuality
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in media buffering benchmark, driven by `scripts/media_bench_device.py`.
 *
 * Each scenario runs the app's real media stack (feed and Viewer player pools, the shared video cache, the scrub
 * frame source, the Viewer prefetch owner and Coil's loader) against [BenchMediaServer], a throttled loopback
 * server serving generated media the script pushed to `cache/media-bench`, so no provider media is involved.
 * Every run uses fresh URLs, so caches start cold, and the server reports bytes per path, including re-downloaded
 * ranges. Results are reported as one JSON instrumentation status (`mediaBench`). Without the `mediaBench`
 * argument every test is skipped.
 */
@RunWith(AndroidJUnit4::class)
class MediaBufferingBenchmarkTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val arguments = InstrumentationRegistry.getArguments()
    private val nonce = UUID.randomUUID().toString().take(8)
    private val headers = SourceKey.GELBOORU.requestHeaders()
    private lateinit var server: BenchMediaServer

    @Before
    fun startServer() {
        assumeTrue("Pass -e mediaBench true to run the media benchmark", arguments.getString("mediaBench") == "true")
        check(context.packageName == "com.theoriacodex.debug")
        val media = File(context.cacheDir, "media-bench")
        check(File(media, "long_1.mp4").isFile) { "Push the benchmark media with scripts/media_bench_device.py" }
        val megabitsPerSecond = arguments.getString("mediaBenchRateMbps")?.toDouble() ?: DEFAULT_RATE_MBPS
        server = BenchMediaServer(
            root = media,
            bytesPerSecond = (megabitsPerSecond * 1_000_000 / 8).toLong(),
            ttfbMs = arguments.getString("mediaBenchTtfbMs")?.toLong() ?: DEFAULT_TTFB_MS,
        )
    }

    @After
    fun stopServer() {
        if (::server.isInitialized) server.close()
    }

    /** Four autoplaying feed cards, then the first opens in the Viewer and loops twice. */
    @Test
    fun feedToViewer() = runBlocking {
        val clips = (1..FEED_CARDS).map { url("clip_$it.mp4") }
        val infrastructure = context.videoPlaybackInfrastructure()
        warmUpVideo()
        resetServer()
        val surfaces = List(FEED_CARDS + 1) { BenchSurface() }
        val feed = onMain {
            clips.mapIndexed { index, clip ->
                val lease = infrastructure.feedPreviewPlayerPool.acquire(clip, headers)
                PlayerProbe(lease.player, surfaces[index].surface).also { probe ->
                    probe.attach()
                    lease.player.playWhenReady = true
                } to lease
            }
        }
        val feedFirstFrames = feed.map { (probe, _) -> probe.awaitFirstFrame() }
        delay(FEED_WATCH_MS)

        // Opening the Viewer pauses the cards at once and returns their leases after the release grace.
        val atOpen = serverStats()
        val viewer = onMain {
            feed.forEach { (probe, _) -> probe.player.playWhenReady = false }
            val lease = infrastructure.viewerVideoPlayerPool.acquire(clips.first(), headers)
            PlayerProbe(lease.resource, surfaces.last().surface).also { probe ->
                probe.attach()
                lease.resource.playWhenReady = true
            } to lease
        }
        delay(FEED_PLAYER_RELEASE_GRACE_MS)
        val feedAtRecycle = onMain {
            feed.map { (probe, lease) ->
                val state = JSONObject()
                    .put("bufferedMs", probe.player.totalBufferedDuration)
                    .put("loading", probe.player.isLoading)
                probe.detach()
                infrastructure.feedPreviewPlayerPool.recycle(lease, retainBinding = true)
                state
            }
        }
        val (probe, lease) = viewer
        val viewerFirstFrame = probe.awaitFirstFrame()
        val viewerPlaying = probe.awaitPlaying()
        delay(CLIP_WATCH_MS)
        val end = serverStats()
        val feedAtEnd = onMain {
            feed.map { (feedProbe, _) ->
                JSONObject().put("state", feedProbe.player.playbackState).put("loading", feedProbe.player.isLoading)
                    .put("bufferedMs", feedProbe.player.totalBufferedDuration)
            }
        }
        onMain {
            probe.detach()
            infrastructure.viewerVideoPlayerPool.recycle(lease)
        }
        surfaces.forEach(BenchSurface::close)
        val cached = clips.map { infrastructure.cachedBytes(it, headers) }
        val clip = end.path("/clip_1.mp4")
        val otherClipsAfterOpen = (2..FEED_CARDS).sumOf { index ->
            end.path("/clip_$index.mp4").optLong("bytes") - atOpen.path("/clip_$index.mp4").optLong("bytes")
        }
        clips.forEach { infrastructure.removeCached(it, headers) }
        report(
            "feedToViewer",
            JSONObject()
                .put("feed_preview_ttff_ms", median(feedFirstFrames))
                .put("viewer_ttff_from_feed_ms", viewerFirstFrame)
                .put("viewer_time_to_playing_from_feed_ms", viewerPlaying)
                .put("viewer_rebuffer_count", probe.rebufferCount)
                .put("viewer_rebuffer_ms", probe.rebufferMs)
                .put("clip_bytes_per_view_mb", megabytes(clip.optLong("bytes")))
                .put("clip_duplicate_mb", megabytes(clip.optLong("duplicateBytes")))
                .put("clip_requests", clip.optInt("requests"))
                .put("feed_bytes_after_viewer_open_mb", megabytes(otherClipsAfterOpen))
                .put("server", end.json)
                .put(
                    "diagnostics",
                    JSONObject().put("feedAtRecycle", JSONArray(feedAtRecycle)).put("feedAtEnd", JSONArray(feedAtEnd))
                        .put("cachedBytes", JSONArray(cached)),
                ),
        )
    }

    /** A long video opened directly: cold first frame, four seeks past the buffer, scrub previews, and a release seek. */
    @Test
    fun viewerSeekAndScrub() = runBlocking {
        val video = url("long_1.mp4")
        val infrastructure = context.videoPlaybackInfrastructure()
        warmUpVideo()
        resetServer()
        val surface = BenchSurface()
        val (probe, lease) = onMain {
            val lease = infrastructure.viewerVideoPlayerPool.acquire(video, headers)
            PlayerProbe(lease.resource, surface.surface).also { probe ->
                probe.attach()
                lease.resource.playWhenReady = true
            } to lease
        }
        val firstFrame = probe.awaitFirstFrame()
        delay(SEEK_SETTLE_MS)
        val duration = onMain { probe.player.duration }
        val seeks = SEEK_FRACTIONS.map { fraction ->
            probe.seekAndAwaitReady((duration * fraction).toLong()).also { delay(SEEK_SETTLE_MS) }
        }

        val beforeScrub = serverStats()
        val density = context.resources.displayMetrics.density
        val frameSize = viewerScrubFrameSize(16f / 9f)
        val frames = onMain {
            ScrubFrameSource(
                context, video, SourceKey.GELBOORU,
                (frameSize.width.value * density).roundToInt() and 1.inv(),
                (frameSize.height.value * density).roundToInt() and 1.inv(),
            )
        }
        val scrubFrames = SCRUB_FRACTIONS.map { fraction ->
            val started = SystemClock.elapsedRealtime()
            withContext(Dispatchers.Main) { frames.frameAt((duration * fraction).toLong()) }
            SystemClock.elapsedRealtime() - started
        }
        val afterScrub = serverStats()
        // Lifting the finger closes the preview and seeks the player to the last previewed position.
        onMain { frames.close() }
        val releaseSeek = probe.seekAndAwaitReady((duration * SCRUB_FRACTIONS.last()).toLong())
        delay(SEEK_SETTLE_MS)
        val end = serverStats()
        onMain {
            probe.detach()
            infrastructure.viewerVideoPlayerPool.recycle(lease)
        }
        surface.close()
        infrastructure.removeCached(video, headers)
        val path = end.path("/long_1.mp4")
        report(
            "viewerSeekAndScrub",
            JSONObject()
                .put("viewer_ttff_cold_ms", firstFrame)
                .put("seek_to_resume_ms", median(seeks))
                .put("seek_to_resume_max_ms", seeks.max())
                .put("seeks_ms", JSONArray(seeks))
                .put("scrub_first_frame_ms", scrubFrames.first())
                .put("scrub_frame_ms", median(scrubFrames))
                .put("scrub_frames_ms", JSONArray(scrubFrames))
                .put(
                    "scrub_bytes_mb",
                    megabytes(afterScrub.path("/long_1.mp4").optLong("bytes") - beforeScrub.path("/long_1.mp4").optLong("bytes")),
                )
                .put("seek_after_scrub_ms", releaseSeek)
                .put("long_bytes_mb", megabytes(path.optLong("bytes")))
                .put("long_duplicate_mb", megabytes(path.optLong("duplicateBytes")))
                .put("server", end.json),
        )
    }

    /** An nhentai-shaped 80-page gallery: open, read 40 pages, then skim 35 pages through the Viewer owner. */
    @Test
    fun galleryPaging() = runBlocking {
        val pages = (1..GALLERY_PAGES).map { index -> ImageRef(url("gallery/page_%03d.jpg".format(index)), null, "image/jpeg") }
        val post = Post(
            id = PostId(SourceKey.NHENTAI, "media-bench-$nonce"), preview = pages.first(), full = pages.first(), media = pages,
            pageUrl = null, width = 1280, height = 1808, canonicalTags = emptyList(), rawTags = emptyList(),
            authorName = null, createdAtEpochMs = 1L, mediaCount = pages.size,
        )
        // The loader, its OkHttp client and the disk cache journal are ready before any Viewer opens.
        context.imageLoader.execute(
            buildViewerImageRequest(context, "${server.baseUrl}/gallery/page_080.jpg?warmup=$nonce", SourceKey.NHENTAI),
        )
        resetServer()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val viewer = ViewerViewModel(
            SavedStateHandle(),
            mediaPrefetcher = viewerMediaPrefetcher(context) { VideoQuality.AUTO },
            scopeOverride = scope,
        )
        scope.launch { viewer.effects.collect { } }
        val memory = MemorySampler(scope)
        val opened = SystemClock.elapsedRealtime()
        onMain {
            viewer.replaceSession(
                ViewerSession(listOf(post), ViewerLaunchContext("media-bench", 0, ViewerStreamSource.SEARCH, 0)),
            )
            viewer.queueAdjacentPrefetch()
        }
        val first = display(post, 0)
        val openToFirstPage = SystemClock.elapsedRealtime() - opened
        val sources = mutableMapOf<String, Int>()
        sources.merge(first.source, 1, Int::plus)

        val reading = (1..READ_PAGES).map { index ->
            onMain { viewer.selectAndPrefetch(index) }
            val ahead = pagesReadyAhead(post, index)
            val shown = display(post, index)
            sources.merge(shown.source, 1, Int::plus)
            delay(READ_DWELL_MS)
            shown.elapsedMs to ahead
        }
        var skimShown = 0
        val skimLatencies = mutableListOf<Long>()
        (READ_PAGES + 1..READ_PAGES + SKIM_PAGES).forEach { index ->
            val flipped = SystemClock.elapsedRealtime()
            onMain { viewer.selectAndPrefetch(index) }
            val pending = scope.async { display(post, index) }
            val shown = withTimeoutOrNull(SKIM_DWELL_MS) { pending.await() }
            if (shown != null) {
                skimShown += 1
                skimLatencies += shown.elapsedMs
                delay((SKIM_DWELL_MS - (SystemClock.elapsedRealtime() - flipped)).coerceAtLeast(0L))
            } else {
                pending.cancel()
            }
        }
        val end = serverStats()
        val peak = memory.stop()
        scope.cancel()
        val galleryBytes = end.json.keys().asSequence().filter { it.startsWith("/gallery/") }
            .sumOf { end.json.getJSONObject(it).optLong("bytes") }
        val galleryDuplicates = end.json.keys().asSequence().filter { it.startsWith("/gallery/") }
            .sumOf { end.json.getJSONObject(it).optLong("duplicateBytes") }
        pages.forEach { ref -> context.imageLoader.diskCache?.remove(requireNotNull(ref.url)) }
        context.imageLoader.memoryCache?.clear()
        val readLatencies = reading.map { it.first }
        report(
            "galleryPaging",
            JSONObject()
                .put("gallery_open_to_first_page_ms", openToFirstPage)
                .put("page_load_p50_ms", percentile(readLatencies, 50))
                .put("page_load_p90_ms", percentile(readLatencies, 90))
                .put("pages_ready_ahead", reading.map { it.second }.average())
                .put("skim_shown_pct", 100.0 * skimShown / SKIM_PAGES)
                .put("skim_shown_p50_ms", if (skimLatencies.isEmpty()) JSONObject.NULL else percentile(skimLatencies, 50))
                .put("peak_memory_delta_mb", megabytes(peak.peakBytes - peak.baselineBytes))
                .put("peak_memory_mb", megabytes(peak.peakBytes))
                .put("gallery_bytes_mb", megabytes(galleryBytes))
                .put("gallery_duplicate_mb", megabytes(galleryDuplicates))
                .put("display_sources", JSONObject(sources.toMap())),
        )
    }

    /** One Viewer player plays its first frame, so codec and player setup costs are paid before measuring. */
    private suspend fun warmUpVideo() {
        val infrastructure = context.videoPlaybackInfrastructure()
        val location = "${server.baseUrl}/clip_4.mp4?warmup=$nonce"
        BenchSurface().use { surface ->
            val (probe, lease) = onMain {
                val lease = infrastructure.viewerVideoPlayerPool.acquire(location, headers)
                PlayerProbe(lease.resource, surface.surface).also { probe ->
                    probe.attach()
                    lease.resource.playWhenReady = true
                } to lease
            }
            probe.awaitFirstFrame()
            onMain {
                probe.detach()
                infrastructure.viewerVideoPlayerPool.recycle(lease)
            }
        }
        infrastructure.removeCached(location, headers)
    }

    private fun ViewerViewModel.queueAdjacentPrefetch() {
        onAction(viewerPrefetchAction(state.value.pages, state.value.currentMedia?.key, null))
    }

    private fun ViewerViewModel.selectAndPrefetch(mediaIndex: Int) {
        onAction(ViewerAction.SelectMedia(mediaIndex))
        queueAdjacentPrefetch()
    }

    /** The Viewer page's own request: its location and the pager page's size, as AsyncImage resolves it. */
    private suspend fun display(post: Post, index: Int): Shown {
        val location = viewerMediaDeliveryPlan(post, post.media[index]).candidates.first().location
        val metrics = context.resources.displayMetrics
        val request = buildViewerImageRequest(context, location, post.id.source).newBuilder()
            .size(metrics.widthPixels, metrics.heightPixels)
            .precision(Precision.INEXACT)
            .scale(Scale.FIT)
            .build()
        val started = SystemClock.elapsedRealtime()
        val result = context.imageLoader.execute(request)
        check(result is SuccessResult) { "Gallery page $index failed: $result" }
        return Shown(SystemClock.elapsedRealtime() - started, result.dataSource.name.lowercase())
    }

    private fun pagesReadyAhead(post: Post, current: Int): Int =
        (current + 1..minOf(current + READY_AHEAD_WINDOW, post.media.lastIndex)).count { index ->
            val location = requireNotNull(post.media[index].url)
            context.imageLoader.diskCache?.openSnapshot(location)?.use { true } == true
        }

    private fun url(path: String) = "${server.baseUrl}/$path?run=$nonce"

    private fun resetServer() = server.reset()

    private fun serverStats() = ServerStats(server.snapshot())

    private fun report(scenario: String, metrics: JSONObject) {
        val payload = JSONObject().put("scenario", scenario).put("metrics", metrics).toString()
        Log.i(LOG_TAG, payload)
        instrumentation.sendStatus(0, Bundle().apply { putString("mediaBench", payload) })
    }

    private suspend fun <T> onMain(block: () -> T): T = withContext(Dispatchers.Main) { block() }

    private data class Shown(val elapsedMs: Long, val source: String)

    private class ServerStats(val json: JSONObject) {
        fun path(path: String): JSONObject = json.optJSONObject(path) ?: JSONObject()
    }
}

/** Times one player's first frame, playback start, rebuffers and seek completions; attach and detach on main. */
private class PlayerProbe(val player: ExoPlayer, private val surface: Surface) : Player.Listener {
    private val attachedAt = SystemClock.elapsedRealtime()
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
        player.setVideoSurface(surface)
    }

    fun detach() {
        player.removeListener(this)
        player.clearVideoSurface(surface)
    }

    suspend fun awaitFirstFrame(): Long = withTimeout(PLAYER_TIMEOUT_MS) { firstFrame.await() } - attachedAt

    suspend fun awaitPlaying(): Long = withTimeout(PLAYER_TIMEOUT_MS) { playing.await() } - attachedAt

    /** Milliseconds from the seek request until the player is ready to play at [positionMs]. */
    suspend fun seekAndAwaitReady(positionMs: Long): Long {
        val ready = CompletableDeferred<Long>()
        val started = withContext(Dispatchers.Main) {
            seekReady = ready
            SystemClock.elapsedRealtime().also { player.seekTo(positionMs) }
        }
        return withTimeout(PLAYER_TIMEOUT_MS) { ready.await() } - started
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
private class BenchSurface : AutoCloseable {
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
private class MemorySampler(scope: CoroutineScope) {
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

private data class MemoryPeak(val baselineBytes: Long, val peakBytes: Long)

private fun median(values: List<Long>): Long = percentile(values, 50)

private fun percentile(values: List<Long>, percentile: Int): Long {
    val sorted = values.sorted()
    return sorted[((sorted.size - 1) * percentile / 100.0).roundToInt()]
}

private fun megabytes(bytes: Long): Double = (bytes / 10_485.76).roundToInt() / 100.0

private const val LOG_TAG = "TheoriaMediaBench"
private const val DEFAULT_RATE_MBPS = 16.0
private const val DEFAULT_TTFB_MS = 100L
private const val FEED_CARDS = 4
private const val FEED_WATCH_MS = 5_000L
private const val CLIP_WATCH_MS = 31_000L
private const val SEEK_SETTLE_MS = 1_500L
private val SEEK_FRACTIONS = listOf(0.50, 0.85, 0.25, 0.65)
private val SCRUB_FRACTIONS = listOf(0.40, 0.72, 0.93)
private const val GALLERY_PAGES = 80
private const val READ_PAGES = 40
private const val READ_DWELL_MS = 1_200L
private const val SKIM_PAGES = 35
private const val SKIM_DWELL_MS = 200L
private const val READY_AHEAD_WINDOW = 10
private const val PLAYER_TIMEOUT_MS = 30_000L
private const val MEMORY_SAMPLE_MS = 50L
private const val SURFACE_WIDTH = 1920
private const val SURFACE_HEIGHT = 1080
private const val SURFACE_IMAGES = 4
