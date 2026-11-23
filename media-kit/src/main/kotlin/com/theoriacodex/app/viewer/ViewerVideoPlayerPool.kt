@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.theoriacodex.app.viewer

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Trace
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ScrubbingModeParameters
import androidx.media3.exoplayer.SeekParameters

internal const val VIEWER_PLAYER_IDLE_TIMEOUT_MS = 30_000L

/** Return stops media immediately; decoder/thread release never belongs to a page's disposal. */
class ViewerVideoPlayerPool(
    private val infrastructure: VideoPlaybackInfrastructure,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val slots = ReusableVideoSlotPool<ExoPlayer, VideoPlaybackIdentity>(
        maxIdleResources = 1,
        idleTimeoutMs = VIEWER_PLAYER_IDLE_TIMEOUT_MS,
        clock = clock,
        createResource = ::createPlayer,
    )
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

    /** A player prepared with [playback]; give it back with [recycle]. */
    fun acquire(playback: VideoPlaybackRequest): VideoPlayerLease {
        checkMainThread()
        infrastructure.onViewerStreamAcquired()
        val request = infrastructure.bind(playback)
        val lease = slots.acquire(request.identity)
        val player = lease.resource
        traceMediaSection(MediaTraceSections.VIEWER_PREPARE) {
            // Returned players have no media binding; every lease starts at the requested item.
            player.setMediaSource(request.mediaSourceFactory.createMediaSource(request.mediaItem), true)
            player.repeatMode = Player.REPEAT_MODE_ONE
            player.prepare()
        }
        publishCounters()
        scheduleCleanup()
        return VideoPlayerLease(lease)
    }

    fun recycle(lease: VideoPlayerLease) {
        checkMainThread()
        if (!slots.isActive(lease.slotLease)) return
        traceMediaSection(MediaTraceSections.VIEWER_PLAYER_RETURN) {
            // A page can leave mid-drag, after its player's binding let go; the next lease must not start held.
            lease.player.setScrubbingModeEnabled(false)
            lease.player.pause()
            lease.player.stop()
            lease.player.clearMediaItems()
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

/**
 * An instant-seek drag's move to [targetMs]. Scrubbing mode holds playback and shows the keyframe at or before the
 * target ([VIEWER_SCRUBBING]): one seek runs at a time, a newer target replaces any queued one, and a target in the
 * keyframe group already shown costs nothing. [endScrub] ends it.
 */
fun ExoPlayer.scrubTo(targetMs: Long) {
    if (!isScrubbingModeEnabled) setScrubbingModeEnabled(true)
    seekTo(targetMs)
}

/** Ends an instant-seek drag's scrubbing mode, so playback resumes; returns whether a drag had begun it. */
fun ExoPlayer.endScrub(): Boolean = isScrubbingModeEnabled.also { scrubbing -> if (scrubbing) setScrubbingModeEnabled(false) }

/**
 * Scrubbing mode for instant-seek drags: Media3's defaults, with each seek snapped to the keyframe at or before its
 * target. A keyframe shows after its own bytes and one decode, where an exact seek first downloads and decodes up to
 * the target; the release's exact seek then reads on from the keyframe the drag fetched.
 */
private val VIEWER_SCRUBBING = ScrubbingModeParameters.DEFAULT.buildUpon()
    // Fractions of the duration: any distance before the target, none after it.
    .setFractionalSeekTolerance(1.0, 0.0)
    .build()
