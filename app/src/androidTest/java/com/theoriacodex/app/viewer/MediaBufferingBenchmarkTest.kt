package com.theoriacodex.app.viewer

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
        val cached = clips.map { infrastructure.cachedBytes(it, headers) }
        val returned = returnToFeed(clips, surfaces)
        surfaces.forEach(BenchSurface::close)
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
                .put("feed_return_all_playing_ms", returned.first)
                .put("feed_resume_after_short_viewer_ms", returned.second)
                .put("server", end.json)
                .put(
                    "diagnostics",
                    JSONObject().put("feedAtRecycle", JSONArray(feedAtRecycle)).put("feedAtEnd", JSONArray(feedAtEnd))
                        .put("cachedBytes", JSONArray(cached)),
                ),
        )
    }

    /**
     * A long video opened directly: cold first frame, four seeks past the buffer, then three timeline drags through
     * the page's scrub previews. The first drag rests on three targets past the buffer and releases on the last one,
     * seeking as the timeline does; the second, after playback settles, rests on one target and is cancelled; the
     * third rests between two keyframes in a stretch nothing has loaded and releases there. (The first release lands
     * just past an earlier seek's cached stretch, so only the third needs bytes from before its target.) Stalls while
     * playing, seeks excluded, are counted over the whole session.
     */
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
        val coldPlaying = probe.awaitPlaying()
        delay(SEEK_SETTLE_MS)
        val duration = onMain { probe.player.duration }
        val seeks = SEEK_FRACTIONS.map { fraction ->
            probe.seekAndAwaitReady((duration * fraction).toLong()).also { delay(SEEK_SETTLE_MS) }
        }

        val beforeScrub = serverStats()
        val scrubStartedAt = SystemClock.elapsedRealtime()
        val density = context.resources.displayMetrics.density
        val frameSize = viewerScrubFrameSize(16f / 9f)
        val previews = onMain {
            ViewerScrubPreviews(
                context, probe.player, SourceKey.GELBOORU,
                (frameSize.width.value * density).roundToInt() and 1.inv(),
                (frameSize.height.value * density).roundToInt() and 1.inv(),
            )
        }
        val frameLog = PreviewFrameLog(File(context.cacheDir, "media-bench-preview.png"))
        var afterScrub: ServerStats? = null
        var release: TimedSeek? = null
        val firstDrag = previews.drag(SCRUB_FRACTIONS.map { (duration * it).toLong() }, frameLog) { target ->
            afterScrub = serverStats()
            release = probe.startSeek { previews.seekReleased(target) }
        }
        val scrubFrames = firstDrag.map(DragFrame::elapsedMs)
        val releaseSeek = probe.awaitReady(checkNotNull(release))
        val releasedAt = checkNotNull(release).startedAt - scrubStartedAt
        val releaseLanding = onMain { probe.seekReadyPositionMs }
        delay(SEEK_SETTLE_MS)
        val secondDrag = previews.drag(listOf((duration * SECOND_DRAG_FRACTION).toLong()), frameLog)
        frameLog.saveLatest()
        delay(SEEK_SETTLE_MS)
        var coldRelease: TimedSeek? = null
        val thirdDrag = previews.drag(listOf((duration * COLD_RELEASE_FRACTION).toLong()), frameLog) { target ->
            coldRelease = probe.startSeek { previews.seekReleased(target) }
        }
        val coldReleaseSeek = probe.awaitReady(checkNotNull(coldRelease))
        val coldLanding = onMain { probe.seekReadyPositionMs }
        delay(SEEK_SETTLE_MS)
        val dragEvents = JSONArray(
            (firstDrag + secondDrag + thirdDrag).map { "${it.movedAt - scrubStartedAt}->${it.publishedAt - scrubStartedAt}" },
        )
        val scrubTimeline = server.timeline("/long_1.mp4", scrubStartedAt)
        val seekTimeline = server.timeline("/long_1.mp4", probe.attachedAt)
        val (seekEvents, stalls) = onMain { JSONArray(probe.seekEvents.map(::JSONArray)) to (probe.rebufferCount to probe.rebufferMs) }
        val end = serverStats()
        onMain {
            previews.close()
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
                .put("viewer_time_to_playing_cold_ms", coldPlaying)
                .put("seek_to_resume_ms", median(seeks))
                .put("seek_to_resume_max_ms", seeks.max())
                .put("seeks_ms", JSONArray(seeks))
                .put("seek_rebuffer_count", stalls.first)
                .put("seek_rebuffer_ms", stalls.second)
                .put("scrub_first_frame_ms", scrubFrames.first())
                .put("scrub_frame_ms", median(scrubFrames))
                .put("scrub_frames_ms", JSONArray(scrubFrames))
                .put(
                    "scrub_bytes_mb",
                    megabytes(checkNotNull(afterScrub).path("/long_1.mp4").optLong("bytes") - beforeScrub.path("/long_1.mp4").optLong("bytes")),
                )
                .put("seek_after_scrub_ms", releaseSeek)
                .put("scrub_second_drag_first_frame_ms", secondDrag.first().elapsedMs)
                .put("seek_after_cold_scrub_ms", coldReleaseSeek)
                .put("long_bytes_mb", megabytes(path.optLong("bytes")))
                .put("long_duplicate_mb", megabytes(path.optLong("duplicateBytes")))
                .put("server", end.json)
                .put(
                    "diagnostics",
                    JSONObject().put("scrubTimeline", scrubTimeline).put("dragEvents", dragEvents).put("releasedAt", releasedAt)
                        .put("releaseTargetMs", (duration * SCRUB_FRACTIONS.last()).toLong()).put("releaseLandedMs", releaseLanding)
                        .put("coldReleaseTargetMs", (duration * COLD_RELEASE_FRACTION).toLong()).put("coldReleaseLandedMs", coldLanding)
                        .put("seekEvents", seekEvents).put("seekTimeline", seekTimeline).put("frames", JSONArray(frameLog.frames)),
                ),
        )
    }

    /**
     * Three 90 s videos in one post, opened on the middle one: it plays while the Viewer owner warms its two
     * neighbours, then a swipe leases the right neighbour (the pager's swipe target), makes it current and plays it.
     * The middle video plays for 20 s, or `mediaBenchSwipeWatchMs`.
     */
    @Test
    fun viewerSwipe() = runBlocking {
        val videos = SWIPE_VIDEOS.map { name -> ImageRef(url(name), null, "video/mp4") }
        val post = Post(
            // A Gelbooru post's media would be repaired to https; this loopback server is plain HTTP.
            id = PostId(SourceKey.NHENTAI, "media-bench-swipe-$nonce"), preview = videos[1], full = videos[1],
            media = videos, pageUrl = null, width = 1920, height = 1080, canonicalTags = emptyList(),
            rawTags = emptyList(), authorName = null, createdAtEpochMs = 1L, mediaCount = videos.size,
        )
        // The Viewer's player and prefetcher both bind the post's source headers, so they share a cache entry.
        val swipeHeaders = post.id.source.requestHeaders()
        val infrastructure = context.videoPlaybackInfrastructure()
        warmUpVideo()
        resetServer()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val viewer = ViewerViewModel(
            SavedStateHandle(),
            mediaPrefetcher = viewerMediaPrefetcher(context) { VideoQuality.AUTO },
            scopeOverride = scope,
        )
        scope.launch { viewer.effects.collect { } }
        val surfaces = List(2) { BenchSurface() }
        val current = onMain {
            viewer.replaceSession(
                ViewerSession(
                    listOf(post), ViewerLaunchContext("media-bench", 0, ViewerStreamSource.SEARCH, 0),
                    initialMediaIndex = 1,
                ),
            )
            viewer.queueAdjacentPrefetch()
            viewerProbe(requireNotNull(videos[1].url), surfaces[0], play = true, swipeHeaders)
        }
        current.first.awaitFirstFrame()
        onMain { viewer.markShown(1) }
        delay(arguments.getString("mediaBenchSwipeWatchMs")?.toLong() ?: SWIPE_WATCH_MS)

        // The swipe's target page leases its player as the drag starts; it plays once the pager settles on it.
        val beforeSwipe = serverStats()
        val swipeStarted = SystemClock.elapsedRealtime()
        val target = onMain {
            viewer.onAction(viewerPrefetchAction(viewer.state.value.pages, viewer.state.value.currentMedia?.key,
                viewer.state.value.pages.first().media[2].key))
            viewerProbe(requireNotNull(videos[2].url), surfaces[1], play = false, swipeHeaders)
        }
        delay(SWIPE_SETTLE_MS)
        onMain {
            current.first.detach()
            infrastructure.viewerVideoPlayerPool.recycle(current.second)
            viewer.selectAndPrefetch(2)
            target.first.player.playWhenReady = true
        }
        val swipeFirstFrame = target.first.awaitFirstFrameAt() - swipeStarted
        val swipePlaying = target.first.awaitPlayingAt() - swipeStarted
        onMain { viewer.markShown(2) }
        delay(SWIPE_AFTER_MS)
        val end = serverStats()
        onMain {
            target.first.detach()
            infrastructure.viewerVideoPlayerPool.recycle(target.second)
        }
        scope.cancel()
        surfaces.forEach(BenchSurface::close)
        videos.forEach { infrastructure.removeCached(requireNotNull(it.url), swipeHeaders) }
        val bytes = SWIPE_VIDEOS.map { end.path("/$it").optLong("bytes") }
        report(
            "viewerSwipe",
            JSONObject()
                .put("swipe_ttff_ms", swipeFirstFrame)
                .put("swipe_time_to_playing_ms", swipePlaying)
                .put("swipe_rebuffer_count", target.first.rebufferCount)
                .put("swipe_target_bytes_before_mb", megabytes(beforeSwipe.path("/${SWIPE_VIDEOS[2]}").optLong("bytes")))
                .put("swipe_unvisited_neighbour_mb", megabytes(bytes[0]))
                .put("swipe_session_mb", megabytes(bytes.sum()))
                .put("server", end.json),
        )
    }

    /**
     * Four autoplaying feed cards; a scroll moves the first two off screen (paused, then returned warm, as a
     * disposed card does) and brings two new cards on, which must autoplay with the rest. After the new cards
     * play for a while, scrolling back returns the first two.
     */
    @Test
    fun feedScroll() = runBlocking {
        val clips = (1..FEED_SCROLL_CLIPS).map { url("clip_$it.mp4") }
        val infrastructure = context.videoPlaybackInfrastructure()
        warmUpVideo()
        resetServer()
        val surfaces = List(FEED_SCROLL_CLIPS) { BenchSurface() }
        val shown = onMain { (0 until FEED_CARDS).associateWith { feedCard(clips[it], surfaces[it]) }.toMutableMap() }
        shown.values.forEach { it.first.awaitPlaying() }
        delay(FEED_SCROLL_WATCH_MS)

        val scrolledAt = SystemClock.elapsedRealtime()
        val atScroll = serverStats()
        onMain { SCROLLED_OFF.forEach { shown.getValue(it).first.player.playWhenReady = false } }
        delay(FEED_SCROLL_OFF_MS)
        val entered = onMain {
            SCROLLED_OFF.forEach { index -> shown.remove(index)?.let { (probe, lease) -> returnFeedCard(probe, lease) } }
            SCROLLED_ON.associateWith { feedCard(clips[it], surfaces[it]) }.also(shown::putAll)
        }
        val enteredFirstFrames = entered.values.map { it.first.awaitFirstFrameAt() - scrolledAt }
        shown.values.forEach { it.first.awaitPlaying() }
        val allPlaying = SystemClock.elapsedRealtime() - scrolledAt
        delay(FEED_SCROLL_AWAY_MS)
        val away = serverStats()
        val scrolledPast = SCROLLED_OFF.sumOf { index ->
            val path = "/clip_${index + 1}.mp4"
            away.path(path).optLong("bytes") - atScroll.path(path).optLong("bytes")
        }

        val backAt = SystemClock.elapsedRealtime()
        onMain { SCROLLED_ON.forEach { shown.getValue(it).first.player.playWhenReady = false } }
        delay(FEED_SCROLL_OFF_MS)
        val returned = onMain {
            SCROLLED_ON.forEach { index -> shown.remove(index)?.let { (probe, lease) -> returnFeedCard(probe, lease) } }
            SCROLLED_OFF.associateWith { feedCard(clips[it], surfaces[it]) }.also(shown::putAll)
        }
        returned.values.forEach { it.first.awaitPlaying() }
        val backPlaying = SystemClock.elapsedRealtime() - backAt
        check(onMain { shown.values.all { it.first.player.isPlaying } }) { "Every visible card must autoplay" }
        onMain { shown.values.forEach { (probe, lease) -> returnFeedCard(probe, lease) } }
        surfaces.forEach(BenchSurface::close)
        clips.forEach { infrastructure.removeCached(it, headers) }
        report(
            "feedScroll",
            JSONObject()
                .put("feed_scroll_ttff_ms", median(enteredFirstFrames))
                .put("feed_scroll_all_playing_ms", allPlaying)
                .put("feed_scrolled_past_mb", megabytes(scrolledPast))
                .put("feed_scroll_back_playing_ms", backPlaying)
                .put("feed_scroll_session_mb", megabytes(clips.sumOf { away.path(it.toPath()).optLong("bytes") }))
                .put("server", serverStats().json),
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
        onMain { viewer.markShown(0) }
        val openToFirstPage = SystemClock.elapsedRealtime() - opened
        val sources = mutableMapOf<String, Int>()
        sources.merge(first.source, 1, Int::plus)

        val reading = (1..READ_PAGES).map { index ->
            onMain { viewer.selectAndPrefetch(index) }
            val ahead = pagesReadyAhead(post, index)
            val shown = display(post, index)
            onMain { viewer.markShown(index) }
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
                onMain { viewer.markShown(index) }
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

    /**
     * Closing the Viewer: the four cards lease again and must all play (every visible card autoplays). Then a
     * Viewer opens and closes within the release grace, so the cards keep their stopped leases and resume, as
     * the card's effect does. Returns how long until all four were playing in each case.
     */
    private suspend fun returnToFeed(clips: List<String>, surfaces: List<BenchSurface>): Pair<Long, Long> {
        val infrastructure = context.videoPlaybackInfrastructure()
        val returnedAt = SystemClock.elapsedRealtime()
        val cards = onMain {
            clips.mapIndexed { index, clip ->
                val lease = infrastructure.feedPreviewPlayerPool.acquire(clip, headers)
                PlayerProbe(lease.player, surfaces[index].surface).also { probe ->
                    probe.attach()
                    lease.player.playWhenReady = true
                } to lease
            }
        }
        cards.forEach { (probe, _) -> probe.awaitPlaying() }
        val allPlaying = SystemClock.elapsedRealtime() - returnedAt
        val reopened = onMain {
            val lease = infrastructure.viewerVideoPlayerPool.acquire(clips[1], headers)
            infrastructure.viewerVideoPlayerPool.recycle(lease)
            cards.forEach { (probe, _) -> check(probe.player.playbackState == Player.STATE_IDLE) { "Feed player kept loading" } }
            SystemClock.elapsedRealtime()
        }
        val resumed = onMain {
            cards.mapIndexed { index, (probe, lease) ->
                probe.detach()
                PlayerProbe(probe.player, surfaces[index].surface).also { resumedProbe ->
                    resumedProbe.attach()
                    infrastructure.feedPreviewPlayerPool.resume(lease)
                    lease.player.playWhenReady = true
                }
            }
        }
        resumed.forEach { it.awaitPlaying() }
        val resumedAfter = SystemClock.elapsedRealtime() - reopened
        onMain {
            resumed.forEach(PlayerProbe::detach)
            cards.forEach { (_, lease) -> infrastructure.feedPreviewPlayerPool.recycle(lease, retainBinding = true) }
        }
        return allPlaying to resumedAfter
    }

    /** A Viewer page's player for [location], bound to [surface]; it plays at once only when [play] is set. */
    private fun viewerProbe(
        location: String,
        surface: BenchSurface,
        play: Boolean,
        requestHeaders: Map<String, String> = headers,
    ): Pair<PlayerProbe, ReusableVideoSlotPool.Lease<ExoPlayer>> {
        val lease = context.videoPlaybackInfrastructure().viewerVideoPlayerPool.acquire(location, requestHeaders)
        return PlayerProbe(lease.resource, surface.surface).also { probe ->
            probe.attach()
            lease.resource.playWhenReady = play
        } to lease
    }

    /** A visible feed card: it leases its player and autoplays. */
    private fun feedCard(location: String, surface: BenchSurface): Pair<PlayerProbe, FeedPreviewPlayerLease> {
        val lease = context.videoPlaybackInfrastructure().feedPreviewPlayerPool.acquire(location, headers)
        return PlayerProbe(lease.player, surface.surface).also { probe ->
            probe.attach()
            lease.player.playWhenReady = true
        } to lease
    }

    /** A card scrolled out of composition returns its player with its binding, as its disposal does. */
    private fun returnFeedCard(probe: PlayerProbe, lease: FeedPreviewPlayerLease) {
        probe.detach()
        context.videoPlaybackInfrastructure().feedPreviewPlayerPool.recycle(lease, retainBinding = true)
    }

    private fun String.toPath(): String = substringAfter(server.baseUrl).substringBefore('?')

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

    /** What the page's renderer reports once its image is on screen. */
    private fun ViewerViewModel.markShown(mediaIndex: Int) {
        val current = state.value.currentMedia?.takeIf { it.key.mediaIndex == mediaIndex } ?: return
        onAction(ViewerAction.MediaReady(requireNotNull(state.value.session), current.key, current.loadGeneration))
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

private const val LOG_TAG = "TheoriaMediaBench"
private const val DEFAULT_RATE_MBPS = 16.0
private const val DEFAULT_TTFB_MS = 100L
private const val FEED_CARDS = 4
private const val FEED_WATCH_MS = 5_000L
private const val CLIP_WATCH_MS = 31_000L
private const val SEEK_SETTLE_MS = 1_500L
private val SEEK_FRACTIONS = listOf(0.50, 0.85, 0.25, 0.65)
private val SCRUB_FRACTIONS = listOf(0.40, 0.72, 0.93)
private const val SECOND_DRAG_FRACTION = 0.10

/**
 * 41.4 s into the 90 s video, between the 40 s and 42 s keyframes and nearer the later one: nothing loads 37–45 s
 * (the first drag previews 36 s, the first seek starts at 45 s, and the opening seconds are cached again once the
 * looping video passes its end).
 */
private const val COLD_RELEASE_FRACTION = 0.46
private val SWIPE_VIDEOS = listOf("long_2.mp4", "long_1.mp4", "long_3.mp4")
private const val SWIPE_WATCH_MS = 20_000L
private const val SWIPE_SETTLE_MS = 300L
private const val SWIPE_AFTER_MS = 3_000L
private const val FEED_SCROLL_CLIPS = 6
private val SCROLLED_OFF = listOf(0, 1)
private val SCROLLED_ON = listOf(4, 5)
private const val FEED_SCROLL_WATCH_MS = 2_000L
private const val FEED_SCROLL_OFF_MS = 300L
private const val FEED_SCROLL_AWAY_MS = 8_000L
private const val GALLERY_PAGES = 80
private const val READ_PAGES = 40
private const val READ_DWELL_MS = 1_200L
private const val SKIM_PAGES = 35
private const val SKIM_DWELL_MS = 200L
private const val READY_AHEAD_WINDOW = 10
