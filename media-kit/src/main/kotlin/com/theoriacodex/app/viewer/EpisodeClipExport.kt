@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.theoriacodex.app.viewer

import android.content.Context
import android.util.Pair
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.AudioEncoderSettings
import android.util.DisplayMetrics
import android.os.Looper
import android.os.HandlerThread
import android.os.Handler
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.view.View
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DataSpec
import androidx.media3.effect.CanvasOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.TextureOverlay
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.MappingTrackSelector.MappedTrackInfo
import androidx.media3.exoplayer.trackselection.TrackSelector
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import androidx.media3.ui.SubtitleView
import com.google.common.collect.ImmutableList
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.MediaStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

class EpisodeClipException(message: String) : Exception(message)

data class EpisodeClipResult(val subtitlesBurned: Boolean)

suspend fun exportEpisodeClip(
    context: Context,
    infrastructure: VideoPlaybackInfrastructure,
    stream: MediaStream,
    range: EpisodeClipRange,
    tracks: EpisodeClipTracks,
    output: File,
    onProgress: suspend (Int) -> Unit,
): EpisodeClipResult {
    val cues = withContext(Dispatchers.IO) {
        tracks.subtitleIndex?.let { index -> runCatchingPreservingCancellation { loadSubtitleCues(infrastructure, stream, index) }.getOrNull() }
    }
    val playableAudio = stream.audioTracks.filter { it.url.isNotBlank() }
    val chosenAudio = tracks.audioIndex?.let(playableAudio::getOrNull)
    val separateAudio = when {
        chosenAudio != null -> listOf(chosenAudio)
        tracks.streamAudioLabel != null -> emptyList()
        else -> playableAudio
    }
    val bound = infrastructure.bind(VideoPlaybackRequest(stream.url, stream.headers, stream.mime, audioTracks = separateAudio, cache = false))
    val item = bound.mediaItem.buildUpon().setClippingConfiguration(
        MediaItem.ClippingConfiguration.Builder().setStartPositionMs(range.startMs).setEndPositionMs(range.endMs).build(),
    ).build()
    val assets = DefaultAssetLoaderFactory(context, DefaultDecoderFactory.Builder(context).build(), Clock.DEFAULT,
        ClippingSourceFactory(bound.mediaSourceFactory), DataSourceBitmapLoader.Builder(context).build(), clipTrackSelector(forceSeparateAudio = chosenAudio != null, tracks.streamAudioLabel))
    output.parentFile?.mkdirs()
    val thread = HandlerThread("EpisodeClipExport").apply { start() }
    try {
        withContext(Handler(thread.looper).asCoroutineDispatcher()) {
            val video = listOfNotNull(
                Presentation.createForShortSide(EPISODE_CLIP_SHORT_SIDE_PX).copyWithUnsetSideRoundedTo(2),
                cues?.let { OverlayEffect(ImmutableList.of<TextureOverlay>(SubtitleBurnIn(clipSubtitleView(context), it, range.startMs * US_PER_MS))) },
            )
            val composition = Composition.Builder(EditedMediaItemSequence.Builder(
                EditedMediaItem.Builder(item).setEffects(Effects(emptyList(), video)).build(),
            ).build()).setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL).build()
            runTransformer(context, thread.looper, assets, composition, output, onProgress)
        }
    } finally {
        thread.quitSafely()
    }
    return EpisodeClipResult(subtitlesBurned = cues != null)
}

private fun clipTrackSelector(forceSeparateAudio: Boolean, streamAudioLabel: String?) = TrackSelector.Factory { context ->
    val parameters = DefaultTrackSelector.Parameters.Builder()
        .setMinVideoSize(0, EPISODE_CLIP_SHORT_SIDE_PX)
        .setForceLowestBitrate(true)
        .setConstrainAudioChannelCountToDeviceCapabilities(false)
        .apply { streamAudioLabel?.let { setPreferredAudioLabels(it) } }
        .build()
    if (forceSeparateAudio) SeparateAudioTrackSelector(context, parameters) else DefaultTrackSelector(context, parameters)
}

private class SeparateAudioTrackSelector(context: Context, parameters: DefaultTrackSelector.Parameters) :
    DefaultTrackSelector(context, parameters) {
    override fun selectAudioTrack(
        mappedTrackInfo: MappedTrackInfo,
        rendererFormatSupports: Array<Array<IntArray>>,
        rendererMixedMimeTypeAdaptationSupports: IntArray,
        params: DefaultTrackSelector.Parameters,
    ): Pair<ExoTrackSelection.Definition, Int>? {
        for (renderer in 0 until mappedTrackInfo.rendererCount) {
            if (mappedTrackInfo.getRendererType(renderer) != C.TRACK_TYPE_AUDIO) continue
            val groups = mappedTrackInfo.getTrackGroups(renderer)
            val separate = (0 until groups.length).map(groups::get).firstOrNull { it.id.startsWith(SEPARATE_AUDIO_GROUP_PREFIX) }
            if (separate != null) return Pair(ExoTrackSelection.Definition(separate, 0), renderer)
        }
        return super.selectAudioTrack(mappedTrackInfo, rendererFormatSupports, rendererMixedMimeTypeAdaptationSupports, params)
    }
}

private suspend fun runTransformer(
    context: Context,
    looper: Looper,
    assets: DefaultAssetLoaderFactory,
    composition: Composition,
    output: File,
    onProgress: suspend (Int) -> Unit,
) = coroutineScope {
    val encoders = DefaultEncoderFactory.Builder(context)
        .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(EPISODE_CLIP_VIDEO_BITRATE).build())
        .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(EPISODE_CLIP_AUDIO_BITRATE).build())
        .build()
    var finished = false
    var transformer: Transformer? = null
    val progress = launch {
        val holder = ProgressHolder()
        while (true) {
            delay(PROGRESS_POLL_MS)
            if (transformer?.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
        }
    }
    try {
        suspendCancellableCoroutine { continuation ->
            val built = Transformer.Builder(context)
                .setLooper(looper)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(encoders)
                .setAssetLoaderFactory(assets)
                .setEnsureFileStartsOnVideoFrameEnabled(true)
                .setMaxDelayBetweenMuxerSamplesMs(MUXER_STALL_LIMIT_MS)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        finished = true
                        continuation.resume(Unit)
                    }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        finished = true
                        continuation.resumeWithException(EpisodeClipException(clipFailureMessage(exportException.errorCode)))
                    }
                })
                .build()
            transformer = built
            built.start(composition, output.path)
        }
    } finally {
        progress.cancel()
        if (!finished) withContext(NonCancellable) { transformer?.cancel() }
    }
}

internal fun clipFailureMessage(errorCode: Int): String = when (errorCode) {
    in ExportException.ERROR_CODE_IO_UNSPECIFIED until ExportException.ERROR_CODE_DECODER_INIT_FAILED,
    ExportException.ERROR_CODE_MUXING_TIMEOUT ->
        "The episode stopped loading. Clip it again from the episode."
    ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED ->
        "This episode can't be clipped on this device."
    else -> "Could not save this clip. Try again."
}

private fun loadSubtitleCues(infrastructure: VideoPlaybackInfrastructure, stream: MediaStream, index: Int): List<CuesWithTiming>? {
    val track = stream.subtitles.filter { it.url.isNotBlank() }.getOrNull(index) ?: return null
    val bytes = infrastructure.dataSourceFactory(stream.headers).createDataSource().run {
        try {
            open(DataSpec(videoLocationToUri(track.url)))
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(SUBTITLE_READ_CHUNK)
            while (true) {
                val read = read(buffer, 0, buffer.size)
                if (read == C.RESULT_END_OF_INPUT) break
                out.write(buffer, 0, read)
                if (out.size() > SUBTITLE_MAX_BYTES) return null
            }
            out.toByteArray()
        } finally {
            close()
        }
    }
    val mime = sniffedSubtitleMimeType(bytes) ?: subtitleMimeType(track.url)
    val format = Format.Builder().setSampleMimeType(mime).build()
    val parsers = DefaultSubtitleParserFactory()
    if (mime == null || !parsers.supportsFormat(format)) return null
    val cues = mutableListOf<CuesWithTiming>()
    parsers.create(format).parse(bytes, SubtitleParser.OutputOptions.allCues()) { cues += it }
    return cues
}

private class ClippingSourceFactory(private val delegate: MediaSource.Factory) : MediaSource.Factory {
    override fun createMediaSource(mediaItem: MediaItem): MediaSource =
        ClippingMediaSource.Builder(delegate.createMediaSource(mediaItem))
            .setClippingConfiguration(mediaItem.clippingConfiguration)
            .setEnableClippingInMediaPeriod(true)
            .build()

    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory =
        apply { delegate.setDrmSessionManagerProvider(provider) }

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory =
        apply { delegate.setLoadErrorHandlingPolicy(policy) }

    override fun getSupportedTypes(): IntArray = delegate.supportedTypes
}

private fun clipSubtitleView(context: Context): SubtitleView {
    val metrics = context.resources.displayMetrics
    val screenShortSide = minOf(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(1)
    val configuration = Configuration(context.resources.configuration).apply {
        densityDpi = (densityDpi * EPISODE_CLIP_SHORT_SIDE_PX / screenShortSide).coerceAtLeast(DisplayMetrics.DENSITY_LOW)
    }
    return SubtitleView(context.createConfigurationContext(configuration)).apply { applyViewerSubtitleStyle() }
}

internal class SubtitleBurnIn(
    private val view: SubtitleView,
    private val cues: List<CuesWithTiming>,
    private val sourceOffsetUs: Long,
) : CanvasOverlay(true) {
    private var drawnOn: Canvas? = null
    private var drawn: List<CuesWithTiming>? = null

    override fun onDraw(canvas: Canvas, presentationTimeUs: Long) {
        val sourceUs = presentationTimeUs + sourceOffsetUs
        val active = cues.filter { it.startTimeUs <= sourceUs && sourceUs < it.activeUntilUs }
        if (canvas === drawnOn && active == drawn) return
        drawnOn = canvas
        drawn = active
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        if (active.isEmpty()) return
        val subtitles = view
        if (subtitles.width != canvas.width || subtitles.height != canvas.height) {
            subtitles.measure(View.MeasureSpec.makeMeasureSpec(canvas.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(canvas.height, View.MeasureSpec.EXACTLY))
            subtitles.layout(0, 0, canvas.width, canvas.height)
        }
        subtitles.setCues(active.flatMap { it.cues }.map(::sanitizeViewerSubtitleCue))
        subtitles.draw(canvas)
    }
}

private val CuesWithTiming.activeUntilUs: Long get() = if (durationUs == C.TIME_UNSET) Long.MAX_VALUE else startTimeUs + durationUs

private const val SEPARATE_AUDIO_GROUP_PREFIX = "1:"
private const val EPISODE_CLIP_SHORT_SIDE_PX = 540
private const val EPISODE_CLIP_VIDEO_BITRATE = 1_100_000
private const val EPISODE_CLIP_AUDIO_BITRATE = 128_000
private const val MUXER_STALL_LIMIT_MS = 60_000L
private const val PROGRESS_POLL_MS = 500L
private const val SUBTITLE_MAX_BYTES = 8 * 1024 * 1024
private const val SUBTITLE_READ_CHUNK = 16 * 1024
private const val US_PER_MS = 1_000L
