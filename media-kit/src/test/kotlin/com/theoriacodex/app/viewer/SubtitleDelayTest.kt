package com.theoriacodex.app.viewer

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.text.Cue
import androidx.media3.common.util.Clock
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.RendererConfiguration
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.EmptySampleStream
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.extractor.text.CuesWithTiming
import androidx.test.core.app.ApplicationProvider
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
    fun `subtitles end with the video at any delay, never holding playback past its end (ADR 0224)`() {
        renderer.handleMessage(MSG_SET_SUBTITLE_DELAY, 2_000_000L)
        enableAt(0L)
        renderer.render(OFFSET_US + DURATION_US - 1L, 0L)
        assertFalse(renderer.isEnded)
        renderer.render(OFFSET_US + DURATION_US, 0L)
        assertTrue(renderer.isEnded)

        renderer.handleMessage(MSG_SET_SUBTITLE_DELAY, 0L)
        assertTrue(renderer.isEnded)
        renderer.resetPosition(OFFSET_US + DURATION_US / 2, true)
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

    @Test
    fun `a delay change reselects only the subtitle track, so video and audio keep their streams (ADR 0224)`() {
        val selector = SubtitleReloadingTrackSelector(ApplicationProvider.getApplicationContext())
        selector.init({ selector.onParametersActivated(it) }, DefaultBandwidthMeter.getSingletonInstance(ApplicationProvider.getApplicationContext()))
        val subtitles = TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.TEXT_VTT).build())
        val groups = TrackGroupArray(
            TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).setWidth(640).setHeight(360).build()),
            TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setChannelCount(2).setSampleRate(48_000).build()),
            subtitles,
        )
        selector.setParameters(selector.buildUponParameters().setOverrideForType(TrackSelectionOverride(subtitles, 0)))
        val capabilities = arrayOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_TEXT).map(::HandlesItsType).toTypedArray<RendererCapabilities>()
        val periodId = MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0))
        fun select() = selector.selectTracks(capabilities, groups, periodId, timeline)

        val before = select()
        assertEquals(listOf(true, true, true), capabilities.indices.map { before.isRendererEnabled(it) })
        assertTrue(before.isEquivalent(select()))
        selector.reloadText()
        val after = select()

        assertEquals(listOf(true, true, false), capabilities.indices.map { after.isEquivalent(before, it) })
    }

    private class HandlesItsType(private val type: Int) : RendererCapabilities {
        override fun getName() = "handles-$type"
        override fun getTrackType() = type
        override fun supportsFormat(format: Format) =
            RendererCapabilities.create(if (MimeTypes.getTrackType(format.sampleMimeType) == type) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_TYPE)
        override fun supportsMixedMimeTypeAdaptation() = RendererCapabilities.ADAPTIVE_NOT_SUPPORTED
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
