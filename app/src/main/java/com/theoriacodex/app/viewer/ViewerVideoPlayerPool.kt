@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.theoriacodex.app.viewer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Trace
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters

internal const val VIEWER_PLAYER_IDLE_TIMEOUT_MS = 30_000L

/** Return stops media immediately; decoder/thread release never belongs to a page's disposal. */
internal class ViewerVideoPlayerPool(
    context: Context,
    private val infrastructure: VideoPlaybackInfrastructure,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val appContext = context.applicationContext
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

    fun acquire(location: String, headers: Map<String, String>): ReusableVideoSlotPool.Lease<ExoPlayer> {
        checkMainThread()
        val request = infrastructure.bind(location, headers)
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
        return lease
    }

    fun recycle(lease: ReusableVideoSlotPool.Lease<ExoPlayer>) {
        checkMainThread()
        if (!slots.isActive(lease)) return
        traceMediaSection(MediaTraceSections.VIEWER_PLAYER_RETURN) {
            lease.resource.pause()
            lease.resource.stop()
            lease.resource.clearMediaItems()
        }
        slots.recycle(lease, retainBinding = false)
        publishCounters()
        scheduleCleanup()
    }

    fun activePlayerCount(): Int = slots.activeResourceCount

    private fun createPlayer(): ExoPlayer = traceMediaSection(MediaTraceSections.VIEWER_PLAYER_CREATE) {
        ExoPlayer.Builder(appContext)
            .setLoadControl(infrastructure.loadControl(VideoPlaybackProfile.VIEWER))
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
