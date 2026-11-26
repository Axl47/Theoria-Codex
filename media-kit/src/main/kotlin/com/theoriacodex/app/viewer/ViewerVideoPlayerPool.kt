@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.theoriacodex.app.viewer

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Trace
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ScrubbingModeParameters
import androidx.media3.exoplayer.SeekParameters

internal const val VIEWER_PLAYER_IDLE_TIMEOUT_MS = 30_000L

class ViewerVideoPlayerPool(
    private val infrastructure: VideoPlaybackInfrastructure,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val slots = ReusableVideoSlotPool<ExoPlayer, VideoPlaybackRequest>(
        maxIdleResources = 1,
        idleTimeoutMs = VIEWER_PLAYER_IDLE_TIMEOUT_MS,
        clock = clock,
        createResource = ::createPlayer,
    )
    private val separateAudio = HashMap<VideoPlayerLease, FirstSeparateAudio>()
    private val cleanup = object : Runnable {
        override fun run() {
            if (infrastructure.feedPreviewPlayerPool.activePlayerCount() == 0) {
                slots.pollExpired()?.let { player ->
                    traceMediaSection(MediaTraceSections.VIEWER_PLAYER_RELEASE) { player.release() }
                    publishCounters()
                }
            }
            scheduleCleanup(FEED_PREVIEW_RELEASE_SPACING_MS)
        }
    }

    fun acquire(playback: VideoPlaybackRequest, loop: Boolean = true): VideoPlayerLease {
        checkMainThread()
        infrastructure.onViewerStreamAcquired()
        val request = infrastructure.bind(playback)
        val lease = slots.acquire(request.request)
        val player = lease.resource
        traceMediaSection(MediaTraceSections.VIEWER_PREPARE) {
            player.setMediaSource(request.mediaSourceFactory.createMediaSource(request.mediaItem), true)
            player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            player.prepare()
        }
        val playerLease = VideoPlayerLease(lease)
        if (request.mediaItem.localConfiguration?.tag is SeparateAudioLabels) {
            separateAudio[playerLease] = FirstSeparateAudio(player).also(player::addListener)
        }
        publishCounters()
        scheduleCleanup()
        return playerLease
    }

    fun recycle(lease: VideoPlayerLease) {
        checkMainThread()
        if (!slots.isActive(lease.slotLease)) return
        traceMediaSection(MediaTraceSections.VIEWER_PLAYER_RETURN) {
            lease.player.setScrubbingModeEnabled(false)
            lease.player.pause()
            lease.player.stop()
            lease.player.clearMediaItems()
            separateAudio.remove(lease)?.let(lease.player::removeListener)
            lease.player.clearTrackChoices()
        }
        slots.recycle(lease.slotLease, retainBinding = false)
        publishCounters()
        scheduleCleanup()
    }

    fun activePlayerCount(): Int = slots.activeResourceCount

    private fun createPlayer(): ExoPlayer = traceMediaSection(MediaTraceSections.VIEWER_PLAYER_CREATE) {
        infrastructure.playerBuilder(VideoPlaybackProfile.VIEWER)
            .setScrubbingModeParameters(VIEWER_SCRUBBING)
            .build()
            .apply {
                addAnalyticsListener(MediaLoadTraceListener)
                setSeekParameters(SeekParameters.EXACT)
                setAudioAttributes(AudioAttributes.DEFAULT, true)
                setHandleAudioBecomingNoisy(true)
            }
    }

    private fun scheduleCleanup(minimumDelayMs: Long = 0L) {
        handler.removeCallbacks(cleanup)
        val delayMs = slots.nextCleanupDelayMs() ?: return
        handler.postDelayed(cleanup, maxOf(minimumDelayMs, delayMs))
    }

    private fun publishCounters() {
        Trace.setCounter(MediaTraceCounters.VIEWER_ACTIVE_PLAYERS, slots.activeResourceCount.toLong())
        Trace.setCounter(MediaTraceCounters.VIEWER_TOTAL_PLAYERS, slots.totalResourceCount.toLong())
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Viewer players must be leased on the main thread" }
    }
}

fun ExoPlayer.scrubTo(targetMs: Long) {
    if (!isScrubbingModeEnabled) setScrubbingModeEnabled(true)
    seekTo(targetMs)
}

fun ExoPlayer.endScrub(): Boolean = isScrubbingModeEnabled.also { scrubbing -> if (scrubbing) setScrubbingModeEnabled(false) }

private const val SCRUB_SNAP_FRACTION = 0.01

fun scrubPreviewSeekParameters(durationUs: Long, video: Format): SeekParameters {
    val pixels = if (video.width > 0 && video.height > 0) video.width.toLong() * video.height else 0L
    val frameRate = video.frameRate.takeIf { it > 0f } ?: ASSUMED_FRAME_RATE
    val wholeClipPixels = pixels * frameRate * durationUs / US_PER_SECOND
    return if (durationUs <= 0L || pixels == 0L || wholeClipPixels > NEAR_FINGER_DECODE_BUDGET_PIXELS) SeekParameters.CLOSEST_SYNC
    else SeekParameters((durationUs * SCRUB_SNAP_FRACTION).toLong(), 0L)
}

private const val NEAR_FINGER_DECODE_BUDGET_PIXELS = 750_000_000.0
private const val ASSUMED_FRAME_RATE = 30f
private const val US_PER_SECOND = 1_000_000.0

private val VIEWER_SCRUBBING = ScrubbingModeParameters.DEFAULT.buildUpon()
    .setFractionalSeekTolerance(SCRUB_SNAP_FRACTION, 0.0)
    .setDisabledTrackTypes(ScrubbingModeParameters.DEFAULT.disabledTrackTypes + C.TRACK_TYPE_TEXT)
    .build()

private class FirstSeparateAudio(private val player: Player) : Player.Listener {
    override fun onTracksChanged(tracks: Tracks) {
        val group = tracks.groups.firstOrNull { player.separateAudioIndex(it) == 0 } ?: return
        player.removeListener(this)
        val parameters = player.trackSelectionParameters
        if (parameters.overrides.values.any { it.type == C.TRACK_TYPE_AUDIO }) return
        val track = (0 until group.length).firstOrNull(group::isTrackSupported) ?: return
        player.trackSelectionParameters = parameters.buildUpon().setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, track)).build()
    }
}
