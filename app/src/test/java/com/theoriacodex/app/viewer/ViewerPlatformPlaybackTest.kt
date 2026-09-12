@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.theoriacodex.app.viewer

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.media3.exoplayer.ExoPlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ViewerPlatformPlaybackTest {
    @Test fun `external session play and pause update Viewer intent and actual player`() {
        val context = RuntimeEnvironment.getApplication()
        val player = ExoPlayer.Builder(context).build()
        val intents = mutableListOf<Boolean>()
        val session = createViewerMediaSession(context, player) { intents += it }
        try {
            session.player.play()
            assertTrue(player.playWhenReady)
            assertEquals(true, intents.last())
            session.player.pause()
            assertFalse(player.playWhenReady)
            assertEquals(false, intents.last())
            session.player.playWhenReady = true
            assertEquals(true, intents.last())
        } finally { session.release(); player.release() }
    }

    @Test fun `session survives pause for floating playback but closes while stopped`() {
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this)
        }
        var created = 0
        var closed = 0
        val binding = attachViewerMediaSession(owner.lifecycle) {
            created++
            java.io.Closeable { closed++ }
        }
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
        assertEquals(1, created)
        owner.lifecycle.currentState = Lifecycle.State.STARTED
        assertEquals(0, closed)
        owner.lifecycle.currentState = Lifecycle.State.CREATED
        assertEquals(1, closed)
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
        assertEquals(2, created)
        binding.close()
        binding.close()
        assertEquals(2, closed)
    }

    @Test fun `picture in picture ratio preserves portrait and clamps extreme media`() {
        assertEquals(9.0 / 16, viewerPictureInPictureRatio(900, 1600).toDouble(), 0.001)
        assertEquals(2.39, viewerPictureInPictureRatio(10_000, 1).toDouble(), 0.001)
        assertEquals(1 / 2.39, viewerPictureInPictureRatio(1, 10_000).toDouble(), 0.001)
        assertEquals(16.0 / 9, viewerPictureInPictureRatio(null, null).toDouble(), 0.001)
    }
}
