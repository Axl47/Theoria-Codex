@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.theoriacodex.app.viewer

import android.app.Application
import android.os.Looper
import androidx.media3.common.Player
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ViewerVideoPlayerPoolTest {
    @Test
    fun `switch returns stopped empty player without release and reuses it for new media`() {
        val infrastructure = VideoPlaybackInfrastructure(RuntimeEnvironment.getApplication())
        val pool = infrastructure.viewerVideoPlayerPool
        val first = pool.acquire("/first.mp4", emptyMap())
        first.resource.play()

        pool.recycle(first)

        assertFalse(first.resource.isReleased)
        assertFalse(first.resource.playWhenReady)
        assertEquals(Player.STATE_IDLE, first.resource.playbackState)
        assertEquals(0, first.resource.mediaItemCount)
        assertFalse(infrastructure.hasActiveViewerPlayer)
        val second = pool.acquire("/second.mp4", emptyMap())
        assertSame(first.resource, second.resource)
        assertEquals("file:///second.mp4", second.resource.currentMediaItem?.localConfiguration?.uri.toString())
        assertEquals(0L, second.resource.currentPosition)
        second.resource.play()
        pool.recycle(first)
        assertTrue(second.resource.playWhenReady)
        assertEquals(1, pool.activePlayerCount())

        pool.recycle(second)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(VIEWER_PLAYER_IDLE_TIMEOUT_MS + 1))
        assertTrue(second.resource.isReleased)
    }

    @Test
    fun `overlapping bindings remain independent and cleanup waits for active playback`() {
        val infrastructure = VideoPlaybackInfrastructure(RuntimeEnvironment.getApplication())
        val pool = infrastructure.viewerVideoPlayerPool
        val first = pool.acquire("/first.mp4", emptyMap())
        val second = pool.acquire("/second.mp4", emptyMap())
        assertNotSame(first.resource, second.resource)
        assertEquals(2, pool.activePlayerCount())
        pool.recycle(first)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(VIEWER_PLAYER_IDLE_TIMEOUT_MS + 1))
        assertFalse(first.resource.isReleased)
        assertFalse(second.resource.isReleased)
        pool.recycle(second)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(VIEWER_PLAYER_IDLE_TIMEOUT_MS + 1))
        assertTrue(first.resource.isReleased)
        assertTrue(second.resource.isReleased)
    }

    @Test
    fun `feed idle release waits for Viewer and Viewer idle release waits for visible feed`() {
        val infrastructure = VideoPlaybackInfrastructure(RuntimeEnvironment.getApplication())
        val feed = infrastructure.feedPreviewPlayerPool
        val viewer = infrastructure.viewerVideoPlayerPool
        val preview = feed.acquire("/feed.mp4", emptyMap())
        feed.recycle(preview, retainBinding = true)
        val fullscreen = viewer.acquire("/viewer.mp4", emptyMap())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(FEED_PREVIEW_IDLE_TIMEOUT_MS + 1))
        assertFalse(preview.player.isReleased)
        viewer.recycle(fullscreen)
        val visible = feed.acquire("/feed.mp4", emptyMap())
        assertSame(preview.player, visible.player)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(VIEWER_PLAYER_IDLE_TIMEOUT_MS + 1))
        assertFalse(fullscreen.resource.isReleased)
        feed.recycle(visible, retainBinding = true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(FEED_PREVIEW_IDLE_TIMEOUT_MS + 2_000))
        assertTrue(fullscreen.resource.isReleased)
        assertTrue(visible.player.isReleased)
    }
}
