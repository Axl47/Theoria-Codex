@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.theoriacodex.app.viewer

import android.app.Application
import android.os.Looper
import androidx.media3.common.C
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
@Config(application = Application::class)
class ViewerVideoPlayerPoolTest {
    @Test
    fun `switch returns stopped empty player without release and reuses it for new media`() {
        val infrastructure = VideoPlaybackInfrastructure(RuntimeEnvironment.getApplication())
        val pool = infrastructure.viewerVideoPlayerPool
        val first = pool.acquire(VideoPlaybackRequest("/first.mp4", emptyMap()))
        first.player.play()

        pool.recycle(first)

        assertFalse(first.player.isReleased)
        assertFalse(first.player.playWhenReady)
        assertEquals(Player.STATE_IDLE, first.player.playbackState)
        assertEquals(0, first.player.mediaItemCount)
        assertFalse(infrastructure.hasActiveViewerPlayer)
        val second = pool.acquire(VideoPlaybackRequest("/second.mp4", emptyMap()))
        assertSame(first.player, second.player)
        assertEquals("file:///second.mp4", second.player.currentMediaItem?.localConfiguration?.uri.toString())
        assertEquals(0L, second.player.currentPosition)
        second.player.play()
        pool.recycle(first)
        assertTrue(second.player.playWhenReady)
        assertEquals(1, pool.activePlayerCount())

        pool.recycle(second)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(VIEWER_PLAYER_IDLE_TIMEOUT_MS + 1))
        assertTrue(second.player.isReleased)
    }

    @Test
    fun `a lease loops unless the caller says otherwise, and starts without the last lease's track choices`() {
        val pool = VideoPlaybackInfrastructure(RuntimeEnvironment.getApplication()).viewerVideoPlayerPool
        val post = pool.acquire(VideoPlaybackRequest("/post.mp4", emptyMap()))
        assertEquals(Player.REPEAT_MODE_ONE, post.player.repeatMode)
        post.player.selectSubtitle(null)
        pool.recycle(post)

        val episode = pool.acquire(VideoPlaybackRequest("/episode.mp4", emptyMap()), loop = false)
        assertSame(post.player, episode.player)
        assertEquals(Player.REPEAT_MODE_OFF, episode.player.repeatMode)
        assertFalse(C.TRACK_TYPE_TEXT in episode.player.trackSelectionParameters.disabledTrackTypes)
    }

    @Test
    fun `overlapping bindings remain independent and cleanup waits for active playback`() {
        val infrastructure = VideoPlaybackInfrastructure(RuntimeEnvironment.getApplication())
        val pool = infrastructure.viewerVideoPlayerPool
        val first = pool.acquire(VideoPlaybackRequest("/first.mp4", emptyMap()))
        val second = pool.acquire(VideoPlaybackRequest("/second.mp4", emptyMap()))
        assertNotSame(first.player, second.player)
        assertEquals(2, pool.activePlayerCount())
        pool.recycle(first)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(VIEWER_PLAYER_IDLE_TIMEOUT_MS + 1))
        assertFalse(first.player.isReleased)
        assertFalse(second.player.isReleased)
        pool.recycle(second)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(VIEWER_PLAYER_IDLE_TIMEOUT_MS + 1))
        assertTrue(first.player.isReleased)
        assertTrue(second.player.isReleased)
    }

    @Test
    fun `feed idle release waits for Viewer and Viewer idle release waits for visible feed`() {
        val infrastructure = VideoPlaybackInfrastructure(RuntimeEnvironment.getApplication())
        val feed = infrastructure.feedPreviewPlayerPool
        val viewer = infrastructure.viewerVideoPlayerPool
        val preview = feed.acquire("/feed.mp4", emptyMap())
        feed.recycle(preview, retainBinding = true)
        val fullscreen = viewer.acquire(VideoPlaybackRequest("/viewer.mp4", emptyMap()))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(FEED_PREVIEW_IDLE_TIMEOUT_MS + 1))
        assertFalse(preview.player.isReleased)
        viewer.recycle(fullscreen)
        val visible = feed.acquire("/feed.mp4", emptyMap())
        assertSame(preview.player, visible.player)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(VIEWER_PLAYER_IDLE_TIMEOUT_MS + 1))
        assertFalse(fullscreen.player.isReleased)
        feed.recycle(visible, retainBinding = true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(FEED_PREVIEW_IDLE_TIMEOUT_MS + 2_000))
        assertTrue(fullscreen.player.isReleased)
        assertTrue(visible.player.isReleased)
    }
}
