@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.theoriacodex.app.viewer

import android.app.Application
import androidx.media3.exoplayer.ExoPlayer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class VideoPlayerBuilderTest {
    @Test
    fun `feed preview players skip platform diagnostics and Viewer players keep them`() {
        val infrastructure = VideoPlaybackInfrastructure(RuntimeEnvironment.getApplication())
        // ExoPlayer.Builder has no getter for the setting; the field is package-private in Media3 1.11.1.
        val setting = ExoPlayer.Builder::class.java.getDeclaredField("usePlatformDiagnostics")
            .apply { isAccessible = true }
        fun diagnostics(profile: VideoPlaybackProfile) = setting.getBoolean(infrastructure.playerBuilder(profile))

        assertTrue(diagnostics(VideoPlaybackProfile.VIEWER))
        assertFalse(diagnostics(VideoPlaybackProfile.FEED_PREVIEW))
    }
}
