package com.theoriacodex.app.viewer

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.text.Cue
import androidx.media3.common.util.Clock
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.RendererConfiguration
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.EmptySampleStream
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import androidx.media3.extractor.text.CuesWithTiming
import com.google.common.collect.ImmutableList
import com.theoriacodex.domain.model.SubtitleTuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SubtitleDelayTest {
    private val recording = RecordingTextRenderer()
    private val timeline = SinglePeriodTimeline(DURATION_US, true, false, false, null, MediaItem.EMPTY)
    private val renderer = DelayedTextRenderer(recording).apply {
        init(0, PlayerId.UNSET, Clock.DEFAULT)
        setTimeline(timeline)
    }

    @Test
    fun `the subtitle renderer runs behind playback by a positive delay and ahead of it by a negative one (ADR 0224)`() {
        renderer.handleMessage(MSG_SET_SUBTITLE_DELAY, 500_000L)
        enableAt(2_000_000L)
        renderer.render(OFFSET_US + 3_000_000L, 0L)
        assertEquals(OFFSET_US + 1_500_000L, recording.resets.last())
        assertEquals(OFFSET_US + 2_500_000L, recording.renders.last())

        renderer.handleMessage(MSG_SET_SUBTITLE_DELAY, -1_200_000L)
        renderer.resetPosition(OFFSET_US + 4_000_000L, true)
        renderer.render(OFFSET_US + 4_000_000L, 0L)
        assertEquals(OFFSET_US + 5_200_000L, recording.resets.last())
        assertEquals(OFFSET_US + 5_200_000L, recording.renders.last())
    }

    @Test
    fun `a positive delay lets playback end when the final stream reaches its end, not the delay later (ADR 0224)`() {
        renderer.handleMessage(MSG_SET_SUBTITLE_DELAY, 2_000_000L)
        enableAt(0L)
        renderer.setCurrentStreamFinal()
        renderer.render(OFFSET_US + DURATION_US - 1L, 0L)
        assertFalse(renderer.isEnded)
        renderer.render(OFFSET_US + DURATION_US, 0L)
        assertTrue(renderer.isEnded)

        renderer.handleMessage(MSG_SET_SUBTITLE_DELAY, 0L)
        assertFalse(renderer.isEnded)
    }

    @Test
    fun `a clip burns each cue in the delay later, measured from the clip's own start (ADR 0224)`() {
        val cue = CuesWithTiming(ImmutableList.of(Cue.Builder().setText("Hi").build()), 11_000_000L, 1_000_000L)
        val offsetUs = clipSubtitleSourceOffsetUs(EpisodeClipRange(10_000L, 20_000L), SubtitleTuning(delayMs = 500L))

        assertTrue(listOf(cue).activeAt(1_400_000L + offsetUs).isEmpty())
        assertEquals(listOf(cue), listOf(cue).activeAt(1_600_000L + offsetUs))
        assertTrue(listOf(cue).activeAt(2_500_000L + offsetUs).isEmpty())
    }

    private fun enableAt(positionInPeriodUs: Long) {
        renderer.enable(RendererConfiguration.DEFAULT, arrayOf(Format.Builder().build()), EmptySampleStream(),
            OFFSET_US + positionInPeriodUs, false, true, OFFSET_US + positionInPeriodUs, OFFSET_US,
            MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0)))
    }

    private class RecordingTextRenderer : BaseRenderer(C.TRACK_TYPE_TEXT) {
        val renders = mutableListOf<Long>()
        val resets = mutableListOf<Long>()

        override fun getName() = "recording"
        override fun supportsFormat(format: Format) = RendererCapabilities.create(C.FORMAT_HANDLED)
        override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
            renders += positionUs
        }
        override fun onPositionReset(positionUs: Long, joining: Boolean, sampleStreamIsResetToKeyFrame: Boolean) {
            resets += positionUs
        }
        override fun isReady() = true
        override fun isEnded() = false
    }

    private companion object {
        const val OFFSET_US = 1_000_000_000_000L
        const val DURATION_US = 10_000_000L
    }
}
