@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.theoriacodex.app.viewer

import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.Util
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Assert.assertFalse
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

    /** A player of one episode [durationMs] long that only seeks. */
    private class EpisodePlayer(private val durationMs: Long = 1_440_000L) : SimpleBasePlayer(Looper.getMainLooper()) {
        private var positionMs = 0L

        override fun getState(): State = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setPlaylist(listOf(MediaItemData.Builder("episode").setDurationUs(Util.msToUs(durationMs)).build()))
            .setContentPositionMs(positionMs)
            .build()

        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
            this.positionMs = positionMs
            return Futures.immediateVoidFuture()
        }
    }
}
