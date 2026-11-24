@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.theoriacodex.app.viewer

import android.app.Application
import androidx.media3.common.util.Clock
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.DefaultAnalyticsCollector
import androidx.media3.exoplayer.analytics.MediaMetricsListener
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class VideoPlayerBuilderTest {
    @Test
    fun `feed preview players skip platform diagnostics and Viewer players keep them`() {
        val infrastructure = VideoPlaybackInfrastructure(RuntimeEnvironment.getApplication())

        assertTrue(reportsPlatformDiagnostics(infrastructure, VideoPlaybackProfile.VIEWER))
        assertFalse(reportsPlatformDiagnostics(infrastructure, VideoPlaybackProfile.FEED_PREVIEW))
    }

    private fun reportsPlatformDiagnostics(infrastructure: VideoPlaybackInfrastructure, profile: VideoPlaybackProfile): Boolean {
        val listeners = mutableListOf<AnalyticsListener>()
        val collector = object : DefaultAnalyticsCollector(Clock.DEFAULT) {
            override fun addListener(listener: AnalyticsListener) {
                listeners += listener
                super.addListener(listener)
            }
        }
        val player = infrastructure.playerBuilder(profile).setAnalyticsCollector(collector).build()
        try {
            shadowOf(player.playbackLooper).idle()
            shadowOf(player.applicationLooper).idle()
        } finally {
            player.release()
        }
        return listeners.any { it is MediaMetricsListener }
    }
}
