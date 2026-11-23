@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.theoriacodex.app.viewer

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.Util
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Where a Viewer player starts, and when it has been somewhere worth reporting as it lets go. */
@RunWith(RobolectricTestRunner::class)
class ViewerVideoStartTest {
    @Test fun `a player that plays without ever rendering a frame has been at its position`() {
        val start = ViewerVideoStart({ 600_000L }) { _, _ -> false }
        val started = start.applyTo(EpisodePlayer())
        assertFalse(started.atPosition)
        // An audio-only stream plays without a frame.
        started.onIsPlayingChanged(true)
        assertTrue(started.atPosition)
    }

    @Test fun `a player replacing one under way resumes near the end, where only the first starts over`() {
        var stored = 1_438_000L
        val start = ViewerVideoStart({ stored }) { positionMs, durationMs -> positionMs >= durationMs - 5_000L }
        val opening = EpisodePlayer()
        // Opened two seconds from the end, the episode was watched to its end: it begins again.
        start.applyTo(opening).onIsPlayingChanged(true)
        assertEquals(0L, opening.currentPosition)

        // It then plays through the episode, and its stream fails five seconds from the end.
        stored = 1_437_000L
        val replacement = EpisodePlayer()
        start.applyTo(replacement)
        assertEquals(1_437_000L, replacement.currentPosition)
    }

    @Test fun `what showed before the start-over seek does not count as being at the start`() {
        val player = EpisodePlayer(durationMs = C.TIME_UNSET)
        val started = ViewerVideoStart({ 1_438_000L }) { positionMs, durationMs -> positionMs >= durationMs - 5_000L }.applyTo(player)
        // A frame at the resume position, before the duration is known.
        started.onRenderedFirstFrame()
        player.know(1_440_000L)
        assertEquals(0L, player.currentPosition)
        assertFalse(started.atPosition)
        started.onRenderedFirstFrame()
        assertTrue(started.atPosition)
    }

    @Test fun `a player reports nothing before it is at its start, and the start's own seeks never`() {
        val player = EpisodePlayer(durationMs = C.TIME_UNSET)
        val started = ViewerVideoStart({ 1_438_000L }) { positionMs, durationMs -> positionMs >= durationMs - 5_000L }.applyTo(player)
        val reports = mutableListOf<ViewerPlaybackProgress>()
        player.addListener(ViewerSeekReports(started, { false }) { reports += it })
        // The duration starts the episode over: a seek to 0 that the player has not played from yet.
        player.know(1_440_000L)
        // A replay or a controller's seek before it got to its start.
        player.seekTo(30_000L)
        assertEquals(emptyList<ViewerPlaybackProgress>(), reports)
        assertNull(started.progress())
        assertNull(started.releaseProgress())

        started.onIsPlayingChanged(true)
        assertEquals(ViewerPlaybackProgress(30_000, 1_440_000), started.progress())
    }

    @Test fun `every seek of a started player reports where it landed, a drag's at its release`() {
        val player = EpisodePlayer()
        val started = ViewerVideoStart({ 0L }) { _, _ -> false }.applyTo(player).apply { onIsPlayingChanged(true) }
        var scrubbing = false
        val reports = mutableListOf<ViewerPlaybackProgress>()
        player.addListener(ViewerSeekReports(started, { scrubbing }) { reports += it })
        // A paused player that the media session seeks reports the new position, though nothing on screen polled it yet.
        player.seekTo(60_000L)
        scrubbing = true
        repeat(3) { player.seekTo(70_000L + it) }
        scrubbing = false
        player.seekTo(90_000L)
        assertEquals(listOf(ViewerPlaybackProgress(60_000, 1_440_000), ViewerPlaybackProgress(90_000, 1_440_000)), reports)
        assertEquals(ViewerPlaybackProgress(90_000, 1_440_000), started.releaseProgress())
    }

    /** A player of one episode [durationMs] long (unset until [know] gives it) that only seeks. */
    private class EpisodePlayer(private var durationMs: Long = 1_440_000L) : SimpleBasePlayer(Looper.getMainLooper()) {
        private var positionMs = 0L

        /** The timeline arrives, [durationMs] long. */
        fun know(durationMs: Long) {
            this.durationMs = durationMs
            invalidateState()
        }

        override fun getState(): State = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setPlaylist(listOf(MediaItemData.Builder("episode").setDurationUs(if (durationMs > 0L) Util.msToUs(durationMs) else C.TIME_UNSET).build()))
            .setContentPositionMs(positionMs)
            .build()

        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
            this.positionMs = positionMs
            return Futures.immediateVoidFuture()
        }
    }
}
