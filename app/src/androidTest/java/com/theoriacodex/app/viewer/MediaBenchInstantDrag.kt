package com.theoriacodex.app.viewer

import android.media.MediaFormat
import android.os.SystemClock
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

// Instant-seek drag helpers for MediaBufferingBenchmarkTest.

/**
 * Instant-seek drags on a long video [player] that has just started playing, through the Viewer's scrubbing path
 * ([scrubTo], [endScrub]). After a few seconds of playback, three one-second drags, a move every 16 ms, each rest on a
 * target and release there: forward within the buffer, back into the watched stretch, and forward past the buffer.
 * Per drag (`instant_<stretch>_<metric>`): the frames shown while moving and the longest wait between them, the rest
 * (last move to a frame of the target's keyframe group), the release (to the frame at the target), and the bytes
 * downloaded, from [servedBytes].
 */
internal suspend fun instantDrags(player: ExoPlayer, frames: FrameTimes, servedBytes: () -> Long): JSONObject {
    delay(INSTANT_DRAG_WATCH_MS)
    val duration = onMain { player.duration }
    val metrics = JSONObject()
    for (kind in listOf("buffered", "watched", "unbuffered")) {
        val (fromMs, bufferedMs) = onMain { player.currentPosition to player.bufferedPosition }
        val toMs = when (kind) {
            "buffered" -> fromMs + (bufferedMs - fromMs) * 3 / 4
            "watched" -> INSTANT_DRAG_WATCH_MS / 2
            else -> (duration * INSTANT_DRAG_FAR_FRACTION).toLong()
        }
        val before = servedBytes()
        val drag = instantDrag(player, frames, fromMs, toMs)
        delay(INSTANT_DRAG_SETTLE_MS)
        drag.keys().forEach { key -> metrics.put("instant_${kind}_$key", drag.get(key)) }
        metrics.put("instant_${kind}_mb", megabytes(servedBytes() - before))
    }
    return metrics
}

/** One drag from [fromMs] to [toMs] that rests there and releases, timed as [instantDrags] says. */
private suspend fun instantDrag(player: ExoPlayer, frames: FrameTimes, fromMs: Long, toMs: Long): JSONObject {
    val moves = (INSTANT_DRAG_MS / INSTANT_DRAG_STEP_MS).toInt()
    val startedAt = SystemClock.elapsedRealtime()
    var lastMoveAt = startedAt
    for (step in 1..moves) {
        onMain { player.scrubTo(fromMs + (toMs - fromMs) * step / moves) }
        lastMoveAt = SystemClock.elapsedRealtime()
        if (step < moves) delay(INSTANT_DRAG_STEP_MS)
    }
    val shown = frames.since(startedAt).map { it.first }.filter { it <= lastMoveAt }
    val gap = (listOf(startedAt) + shown + lastMoveAt).zipWithNext { a, b -> b - a }.max()
    val restAt = frames.await(lastMoveAt, toMs - INSTANT_DRAG_KEYFRAME_MS..toMs + FRAME_SLACK_MS)
    delay(INSTANT_DRAG_REST_MS)
    val releasedAt = SystemClock.elapsedRealtime()
    onMain {
        player.endScrub()
        player.seekTo(toMs)
    }
    val landedAt = frames.await(releasedAt, toMs - FRAME_SLACK_MS..toMs + FRAME_SLACK_MS)
    return JSONObject()
        .put("drag_frames", shown.size)
        .put("drag_gap_ms", gap)
        .put("rest_ms", restAt - lastMoveAt)
        .put("release_ms", landedAt - releasedAt)
}

private suspend fun <T> onMain(block: () -> T): T = withContext(Dispatchers.Main) { block() }

/** Each rendered frame of a player as (elapsed-realtime clock value, position), both in ms. Attach and detach on main. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class FrameTimes : VideoFrameMetadataListener {
    private val frames = mutableListOf<Pair<Long, Long>>()
    private var player: ExoPlayer? = null

    fun attach(player: ExoPlayer) {
        this.player = player
        player.setVideoFrameMetadataListener(this)
    }

    fun detach(player: ExoPlayer) = player.clearVideoFrameMetadataListener(this)

    /** Called on the playback thread as each frame is released to the surface. */
    override fun onVideoFrameAboutToBeRendered(presentationTimeUs: Long, releaseTimeNs: Long, format: Format, mediaFormat: MediaFormat?) {
        synchronized(frames) { frames += SystemClock.elapsedRealtime() to presentationTimeUs / US_PER_MS }
    }

    fun since(atMs: Long): List<Pair<Long, Long>> = synchronized(frames) { frames.filter { (shownAt, _) -> shownAt >= atMs } }

    /**
     * When a frame whose position is in [positions] was first on screen from [atMs] on: [atMs] itself when the frame
     * on screen then already was, as when the player skips a seek to the keyframe it shows.
     */
    suspend fun await(atMs: Long, positions: LongRange): Long {
        val showing = synchronized(frames) { frames.lastOrNull { (shownAt, _) -> shownAt < atMs } }
        if (showing != null && showing.second in positions) return atMs
        val deadline = SystemClock.elapsedRealtime() + PLAYER_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            since(atMs).firstOrNull { (_, positionMs) -> positionMs in positions }?.let { (shownAt, _) -> return shownAt }
            delay(FRAME_POLL_MS)
        }
        val recent = synchronized(frames) { frames.takeLast(FRAMES_REPORTED) }.map { (shownAt, positionMs) -> "$positionMs@${shownAt - atMs}" }
        val state = withContext(Dispatchers.Main) {
            player?.run { "state $playbackState, loading $isLoading, at $currentPosition, buffered to $bufferedPosition, error $playerError" }
        }
        error("No frame in $positions since ${SystemClock.elapsedRealtime() - atMs} ms ago; last frames $recent; $state")
    }
}

private const val US_PER_MS = 1_000L
private const val FRAME_POLL_MS = 5L
private const val FRAMES_REPORTED = 8

private const val INSTANT_DRAG_WATCH_MS = 6_000L
private const val INSTANT_DRAG_MS = 1_000L
private const val INSTANT_DRAG_STEP_MS = 16L
private const val INSTANT_DRAG_REST_MS = 500L
private const val INSTANT_DRAG_SETTLE_MS = 1_500L
private const val INSTANT_DRAG_FAR_FRACTION = 0.9

/** The benchmark video's keyframe interval (`scripts/media_bench_device.py`), plus a frame. */
private const val INSTANT_DRAG_KEYFRAME_MS = 2_034L

/** About a frame at 30 fps: how far a shown frame may sit from the position it stands for. */
private const val FRAME_SLACK_MS = 40L
