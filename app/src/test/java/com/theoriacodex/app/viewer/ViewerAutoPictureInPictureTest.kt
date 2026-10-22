@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.theoriacodex.app.viewer

import android.app.Activity
import android.app.Application
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.VideoSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ViewerAutoPictureInPictureTest {
    @Test
    fun `native auto entry follows playback and keeps buffering video armed`() {
        withController { activity, controller ->
            val player = TestVideoPlayer()
            val binding = controller.bind(player)
            assertFalse(activity.params!!.isAutoEnterEnabled)
            player.update(playing = true, playbackState = Player.STATE_READY)
            assertTrue(activity.params!!.isAutoEnterEnabled)
            assertTrue(controller.keepPlayingOnPause)
            assertEquals(9.0 / 16, activity.params!!.aspectRatio!!.toDouble(), 0.001)
            player.update(playing = true, playbackState = Player.STATE_BUFFERING)
            assertTrue(activity.params!!.isAutoEnterEnabled)
            player.update(playing = false, playbackState = Player.STATE_READY)
            assertFalse(activity.params!!.isAutoEnterEnabled)
            assertFalse(controller.keepPlayingOnPause)
            player.update(playing = true, playbackState = Player.STATE_ENDED)
            assertFalse(activity.params!!.isAutoEnterEnabled)
            player.update(playing = true, playbackState = Player.STATE_IDLE, failed = true)
            assertFalse(activity.params!!.isAutoEnterEnabled)
            binding.close()
        }
    }

    @Test
    fun `old page disposal cannot disarm the current video and route departure clears auto entry`() {
        withController { activity, controller ->
            val firstPlayer = TestVideoPlayer().apply { update(true, Player.STATE_READY) }
            val first = controller.bind(firstPlayer)
            val secondPlayer = TestVideoPlayer().apply { update(true, Player.STATE_READY) }
            val second = controller.bind(secondPlayer)
            first.close()
            firstPlayer.update(false, Player.STATE_IDLE)
            assertTrue(activity.params!!.isAutoEnterEnabled)
            second.close()
            assertFalse(activity.params!!.isAutoEnterEnabled)
            assertFalse(controller.keepPlayingOnPause)
            secondPlayer.update(true, Player.STATE_READY)
            assertFalse(activity.params!!.isAutoEnterEnabled)
        }
    }

    @Test
    fun `unsupported devices never arm automatic entry`() {
        withController { activity, controller ->
            shadowOf(activity.packageManager).setSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE, false)
            val binding = controller.bind(TestVideoPlayer().apply { update(true, Player.STATE_READY) })
            assertEquals(null, activity.params)
            assertFalse(controller.keepPlayingOnPause)
            binding.close()
        }
    }

    private fun withController(test: (RecordingActivity, ViewerAutoPictureInPicture) -> Unit) {
        val activityController = Robolectric.buildActivity(RecordingActivity::class.java).setup()
        val activity = activityController.get()
        shadowOf(activity.packageManager).setSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE, true)
        try { test(activity, ViewerAutoPictureInPicture(activity)) }
        finally { activityController.pause().stop().destroy() }
    }

    class RecordingActivity : Activity() {
        var params: PictureInPictureParams? = null
        override fun setPictureInPictureParams(params: PictureInPictureParams) { this.params = params }
    }
}

private class TestVideoPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
    private var snapshot = State.Builder().build()
    override fun getState(): State = snapshot

    fun update(playing: Boolean, playbackState: Int, failed: Boolean = false) {
        snapshot = State.Builder()
            .setPlaylist(listOf(MediaItemData.Builder("video").setMediaItem(MediaItem.fromUri("file:///video.mp4")).build()))
            .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(playbackState)
            .setVideoSize(VideoSize(900, 1600))
            .setPlayerError(if (failed) PlaybackException("Failed", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED) else null)
            .build()
        invalidateState()
        shadowOf(Looper.getMainLooper()).idle()
    }
}
