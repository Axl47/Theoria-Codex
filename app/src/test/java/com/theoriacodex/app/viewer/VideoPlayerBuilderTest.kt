@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.theoriacodex.app.viewer

import android.app.Application
import androidx.media3.exoplayer.ExoPlayer
import com.theoriacodex.app.BuildConfig
import org.junit.Assert.assertEquals
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
    fun `only feed preview players follow the platform diagnostics switch`() {
        val infrastructure = VideoPlaybackInfrastructure(RuntimeEnvironment.getApplication())
        // ExoPlayer.Builder has no getter for the setting; the field is package-private in Media3 1.11.1.
        val setting = ExoPlayer.Builder::class.java.getDeclaredField("usePlatformDiagnostics")
            .apply { isAccessible = true }
        fun diagnostics(profile: VideoPlaybackProfile) = setting.getBoolean(infrastructure.playerBuilder(profile))

        assertTrue(diagnostics(VideoPlaybackProfile.VIEWER))
        assertEquals(BuildConfig.PREVIEW_PLATFORM_DIAGNOSTICS, diagnostics(VideoPlaybackProfile.FEED_PREVIEW))
    }
}
