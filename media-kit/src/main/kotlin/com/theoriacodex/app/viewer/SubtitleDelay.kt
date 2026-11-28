@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.theoriacodex.app.viewer

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererConfiguration
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.ForwardingTrackSelection
import androidx.media3.exoplayer.upstream.BandwidthMeter

internal fun subtitleClockUs(playbackUs: Long, delayUs: Long): Long = playbackUs - delayUs

internal class DelayedTextRenderer(renderer: Renderer) : ForwardingRenderer(renderer) {
    private var delayUs = 0L
    private var playbackPositionUs = C.TIME_UNSET
    private var streamOffsetUs = 0L
    private var periodId: MediaSource.MediaPeriodId? = null
    private var timeline = Timeline.EMPTY
    private val period = Timeline.Period()
    internal var requestedDelayMs = 0L

    override fun handleMessage(messageType: Int, message: Any?) {
        if (messageType == MSG_SET_SUBTITLE_DELAY) delayUs = message as Long else super.handleMessage(messageType, message)
    }

    override fun enable(
        configuration: RendererConfiguration,
        formats: Array<out Format>,
        stream: SampleStream,
        positionUs: Long,
        joining: Boolean,
        mayRenderStartOfStream: Boolean,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: MediaSource.MediaPeriodId,
    ) {
        playbackPositionUs = positionUs
        streamOffsetUs = offsetUs
        periodId = mediaPeriodId
        super.enable(configuration, formats, stream, subtitleClockUs(positionUs, delayUs), joining, mayRenderStartOfStream,
            subtitleClockUs(startPositionUs, delayUs), offsetUs, mediaPeriodId)
    }

    override fun replaceStream(
        formats: Array<out Format>,
        stream: SampleStream,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: MediaSource.MediaPeriodId,
    ) {
        streamOffsetUs = offsetUs
        periodId = mediaPeriodId
        super.replaceStream(formats, stream, subtitleClockUs(startPositionUs, delayUs), offsetUs, mediaPeriodId)
    }

    override fun setTimeline(timeline: Timeline) {
        this.timeline = timeline
        super.setTimeline(timeline)
    }

    override fun resetPosition(positionUs: Long, sampleStreamIsResetToKeyFrame: Boolean) {
        playbackPositionUs = positionUs
        super.resetPosition(subtitleClockUs(positionUs, delayUs), sampleStreamIsResetToKeyFrame)
    }

    override fun supportsResetPositionWithoutKeyFrameReset(positionUs: Long): Boolean =
        super.supportsResetPositionWithoutKeyFrameReset(subtitleClockUs(positionUs, delayUs))

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        playbackPositionUs = positionUs
        super.render(subtitleClockUs(positionUs, delayUs), elapsedRealtimeUs)
    }

    override fun getDurationToProgressUs(positionUs: Long, elapsedRealtimeUs: Long): Long =
        super.getDurationToProgressUs(subtitleClockUs(positionUs, delayUs), elapsedRealtimeUs)

    override fun isEnded(): Boolean = super.isEnded() || playbackPassedStreamEnd()

    private fun playbackPassedStreamEnd(): Boolean {
        val id = periodId ?: return false
        val index = timeline.getIndexOfPeriod(id.periodUid)
        if (index == C.INDEX_UNSET || playbackPositionUs == C.TIME_UNSET) return false
        val durationUs = timeline.getPeriod(index, period).durationUs
        return durationUs != C.TIME_UNSET && playbackPositionUs - streamOffsetUs >= durationUs
    }
}

fun ExoPlayer.setSubtitleDelay(delayMs: Long) {
    val renderer = (0 until rendererCount).map(::getRenderer).filterIsInstance<DelayedTextRenderer>().firstOrNull() ?: return
    if (renderer.requestedDelayMs == delayMs) return
    renderer.requestedDelayMs = delayMs
    createMessage(renderer).setType(MSG_SET_SUBTITLE_DELAY).setPayload(Util.msToUs(delayMs)).send()
    val loaded = playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING
    val selector = trackSelector as? SubtitleReloadingTrackSelector ?: return
    if (loaded && currentTracks.isTypeSelected(C.TRACK_TYPE_TEXT)) createMessage { _, _ -> selector.reloadText() }.setLooper(playbackLooper).send()
}

internal class SubtitleReloadingTrackSelector private constructor(
    context: Context,
    private val selections: TextReloadingSelectionFactory,
) : DefaultTrackSelector(context, selections) {
    constructor(context: Context) : this(context, TextReloadingSelectionFactory(AdaptiveTrackSelection.Factory()))

    fun reloadText() {
        selections.generation++
        invalidate(null)
    }
}

private class TextReloadingSelectionFactory(private val delegate: ExoTrackSelection.Factory) : ExoTrackSelection.Factory {
    var generation = 0

    override fun createTrackSelections(
        definitions: Array<out ExoTrackSelection.Definition?>,
        bandwidthMeter: BandwidthMeter,
        mediaPeriodId: MediaSource.MediaPeriodId,
        timeline: Timeline,
    ): Array<ExoTrackSelection?> {
        val created = delegate.createTrackSelections(definitions, bandwidthMeter, mediaPeriodId, timeline)
        val current = generation
        return Array(created.size) { index ->
            created[index]?.let { selection ->
                if (selection.trackGroup.type == C.TRACK_TYPE_TEXT) GenerationTrackSelection(selection, current) else selection
            }
        }
    }
}

private class GenerationTrackSelection(selection: ExoTrackSelection, private val generation: Int) : ForwardingTrackSelection(selection) {
    override fun equals(other: Any?): Boolean = other is GenerationTrackSelection && generation == other.generation && super.equals(other)

    override fun hashCode(): Int = 31 * super.hashCode() + generation
}

internal const val MSG_SET_SUBTITLE_DELAY = Renderer.MSG_CUSTOM_BASE + 1
