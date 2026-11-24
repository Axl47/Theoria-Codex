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

@androidx.annotation.OptIn(UnstableApi::class)
internal class FrameTimes : VideoFrameMetadataListener {
    private val frames = mutableListOf<Pair<Long, Long>>()
    private var player: ExoPlayer? = null

    fun attach(player: ExoPlayer) {
        this.player = player
        player.setVideoFrameMetadataListener(this)
    }

    fun detach(player: ExoPlayer) = player.clearVideoFrameMetadataListener(this)

    override fun onVideoFrameAboutToBeRendered(presentationTimeUs: Long, releaseTimeNs: Long, format: Format, mediaFormat: MediaFormat?) {
        synchronized(frames) { frames += SystemClock.elapsedRealtime() to presentationTimeUs / US_PER_MS }
    }

    fun since(atMs: Long): List<Pair<Long, Long>> = synchronized(frames) { frames.filter { (shownAt, _) -> shownAt >= atMs } }

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

private const val INSTANT_DRAG_KEYFRAME_MS = 2_034L

private const val FRAME_SLACK_MS = 40L
