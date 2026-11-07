@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.theoriacodex.app.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.opengl.GLES20
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Effect
import androidx.media3.common.Format
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlUtil
import androidx.media3.effect.DefaultVideoFrameProcessor
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.PassthroughShaderProgram
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.SingleInputVideoGraph
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.PlaybackVideoGraphWrapper
import androidx.media3.exoplayer.video.VideoFrameReleaseControl
import androidx.media3.exoplayer.video.VideoRendererEventListener
import java.nio.ByteBuffer
import kotlinx.coroutines.CompletableDeferred

/**
 * A Viewer page's scrub preview player: a paused, video-only player on the page's stream that seeks to each target's
 * nearest keyframe and returns that frame at the popup's size. Use it on the main thread; [close] releases it.
 *
 * It replaces Media3's `FrameExtractor`, which a drag had to build anew (its one process-wide player refuses a
 * request while a cancelled one still decodes) and which always prepares and decodes 0:00 before its first seek:
 * about 340 ms before a drag's first request. This player seeks as soon as the stream's index is read, and keeps
 * its decoder and GL pipeline across drags. Its renderer, software decoder, HDR and rotation handling and pixel read
 * follow `FrameExtractor`'s.
 *
 * Between drags it is [idle]: stopped, so no request stays open (a paused request keeps filling socket buffers,
 * about 2 MB, on the link the next seek needs), while foreground mode keeps its decoder for the next drag.
 */
internal class ScrubPreviewPlayer(
    context: Context,
    request: BoundVideoPlaybackRequest,
    widthPx: Int,
    heightPx: Int,
) : ScrubFrameSource {
    private val main = Handler(Looper.getMainLooper())
    private val mediaItem = request.mediaItem
    private val frames = ScrubFrameMatcher<ScrubFrame>()
    private var released = false
    private var attached = true
    private var pending: CompletableDeferred<ScrubFrame>? = null

    private val listener = object : Player.Listener {
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (timeline.isEmpty) return
            val window = timeline.getWindow(0, Timeline.Window())
            // The index is read and the period prepared, so a seek now resolves to a keyframe.
            if (!window.isPlaceholder && window.durationUs != C.TIME_UNSET) act(frames.onIndexed())
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT && pending != null) {
                frames.onSeekResolved(newPosition.positionMs)?.let(::deliver)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            pending?.completeExceptionally(error)
            pending = null
        }
    }

    private val player: ExoPlayer = ExoPlayer.Builder(
        context,
        RenderersFactory { handler, videoListener, _, _, _ -> arrayOf(PreviewFrameRenderer(context, handler, videoListener)) },
        request.mediaSourceFactory,
    )
        .setLoadControl(previewLoadControl())
        .setLooper(Looper.getMainLooper())
        .setPriority(C.PRIORITY_PROCESSING_FOREGROUND)
        .build()
        .apply {
            setForegroundMode(true)
            addListener(listener)
            setSeekParameters(SeekParameters.CLOSEST_SYNC)
            setVideoEffects(
                listOf(
                    Presentation.createForWidthAndHeight(widthPx, heightPx, Presentation.LAYOUT_SCALE_TO_FIT),
                    // glReadPixels reads rows bottom-up.
                    MatrixTransformation { Matrix().apply { setScale(1f, -1f) } },
                    FrameGrabber { frameMs, bitmap ->
                        main.post { if (!released) frames.onFrame(frameMs, ScrubFrame(frameMs, bitmap))?.let(::deliver) }
                    },
                ),
            )
            playWhenReady = false
            setMediaItem(mediaItem)
            prepare()
        }

    override suspend fun frameAt(positionMs: Long): ScrubFrame {
        check(!released) { "Preview player released" }
        val frame = CompletableDeferred<ScrubFrame>()
        pending?.cancel()
        pending = frame
        if (!attached) {
            attached = true
            frames.attach()
            // A new media item re-reads the stream's index, which signals when seeks resolve to keyframes again.
            player.setMediaItem(mediaItem)
            player.prepare()
        }
        act(frames.request(positionMs))
        return try {
            frame.await()
        } finally {
            if (pending === frame) pending = null
        }
    }

    /** Stops loading until the next [frameAt], keeping the decoder; the shown frame is gone with the stream. */
    override fun idle() {
        if (released || !attached) return
        pending?.cancel()
        pending = null
        attached = false
        frames.detach()
        player.stop()
    }

    override fun close() {
        if (released) return
        released = true
        pending?.cancel()
        pending = null
        player.release()
    }

    private fun act(next: ScrubFrameMatcher.Next<ScrubFrame>) = when (next) {
        is ScrubFrameMatcher.Next.Seek -> player.seekTo(next.positionMs)
        is ScrubFrameMatcher.Next.Answer -> deliver(next.frame)
        ScrubFrameMatcher.Next.Wait -> Unit
    }

    private fun deliver(frame: ScrubFrame) {
        pending?.complete(frame)
        pending = null
    }
}

/** A preview frame and the position, in whole milliseconds, of the keyframe it shows. */
internal class ScrubFrame(val positionMs: Long, val bitmap: Bitmap)

/** Where a Viewer page's scrub previews come from ([ScrubPreviewPlayer]); use it on the main thread. */
internal interface ScrubFrameSource : AutoCloseable {
    /** The frame of the keyframe nearest [positionMs]. A newer call replaces a pending one, which is cancelled. */
    suspend fun frameAt(positionMs: Long): ScrubFrame

    /** Stops loading between drags; the next [frameAt] resumes. */
    fun idle()
}

/** A keyframe needs little more than itself; a second covers decoders that hold frames for reordering. */
private fun previewLoadControl() = DefaultLoadControl.Builder()
    .setBufferDurationsMs(PREVIEW_BUFFER_MS, PREVIEW_BUFFER_MS, 0, 0)
    .build()

private const val PREVIEW_BUFFER_MS = 1_000

/**
 * Renders one frame per position reset into the effect chain, and is ready once it has: a paused player then
 * holds the frame of its latest seek. As in `FrameExtractor`, HDR input is read as SDR and rotation is applied as
 * an effect, because the frame never reaches a display surface that would apply it.
 */
private class PreviewFrameRenderer(context: Context, handler: Handler, listener: VideoRendererEventListener) :
    MediaCodecVideoRenderer(
        Builder(context)
            .setMediaCodecSelector(MediaCodecSelector.PREFER_SOFTWARE)
            .setEnableDecoderFallback(true)
            .setAllowedJoiningTimeMs(0)
            .setEventHandler(handler)
            .setEventListener(listener)
            .setMaxDroppedFramesToNotify(0),
    ) {
    private var frameRendered = false
    private var effects: List<Effect> = emptyList()
    private var rotation: Effect? = null

    override fun createPlaybackVideoGraphWrapper(context: Context, releaseControl: VideoFrameReleaseControl): PlaybackVideoGraphWrapper =
        PlaybackVideoGraphWrapper.Builder(context, releaseControl)
            .experimentalSetLateThresholdToDropInputUs(C.TIME_UNSET)
            .setEnablePlaylistMode(true)
            .setClock(clock)
            .setVideoGraphFactory(SingleInputVideoGraph.Factory(DefaultVideoFrameProcessor.Factory.Builder().build()))
            .build()

    override fun onStreamChanged(formats: Array<Format>, startPositionUs: Long, offsetUs: Long, mediaPeriodId: MediaSource.MediaPeriodId) {
        super.onStreamChanged(formats, startPositionUs, offsetUs, mediaPeriodId)
        frameRendered = false
        applyEffects(rotation = null)
    }

    override fun setVideoEffects(effects: List<Effect>) {
        this.effects = effects
        applyEffects(rotation)
    }

    override fun maybeInitializeProcessingPipeline(format: Format): Boolean = super.maybeInitializeProcessingPipeline(
        if (ColorInfo.isTransferHdr(format.colorInfo)) format.buildUpon().setColorInfo(ColorInfo.SDR_BT709_LIMITED).build() else format,
    )

    override fun onInputFormatChanged(formatHolder: FormatHolder): DecoderReuseEvaluation? {
        val format = formatHolder.format
        if (format != null && format.rotationDegrees != 0) {
            applyEffects(ScaleAndRotateTransformation.Builder().setRotationDegrees(FULL_TURN - format.rotationDegrees).build())
            formatHolder.format = format.buildUpon().setRotationDegrees(0).build()
        }
        return super.onInputFormatChanged(formatHolder)
    }

    override fun isReady(): Boolean = frameRendered

    override fun processOutputBuffer(
        positionUs: Long, elapsedRealtimeUs: Long, codec: MediaCodecAdapter?, buffer: ByteBuffer?, bufferIndex: Int,
        bufferFlags: Int, sampleCount: Int, bufferPresentationTimeUs: Long, isDecodeOnlyBuffer: Boolean,
        isLastBuffer: Boolean, format: Format,
    ): Boolean = !frameRendered && super.processOutputBuffer(
        positionUs, elapsedRealtimeUs, codec, buffer, bufferIndex, bufferFlags, sampleCount,
        bufferPresentationTimeUs, isDecodeOnlyBuffer, isLastBuffer, format,
    )

    override fun renderOutputBufferV21(codec: MediaCodecAdapter, index: Int, presentationTimeUs: Long, releaseTimeNs: Long) {
        if (frameRendered) return
        frameRendered = true
        super.renderOutputBufferV21(codec, index, presentationTimeUs, releaseTimeNs)
    }

    override fun onPositionReset(positionUs: Long, joining: Boolean, sampleStreamIsResetToKeyFrame: Boolean) {
        frameRendered = false
        super.onPositionReset(positionUs, joining, sampleStreamIsResetToKeyFrame)
    }

    private fun applyEffects(rotation: Effect?) {
        this.rotation = rotation
        super.setVideoEffects(listOfNotNull(rotation) + effects)
    }
}

private const val FULL_TURN = 360f

/** The last effect: reads each frame it receives into a bitmap and hands it on with its time in milliseconds. */
private class FrameGrabber(private val onFrame: (Long, Bitmap) -> Unit) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = object : PassthroughShaderProgram() {
        private var pixels: ByteBuffer = ByteBuffer.allocateDirect(0)

        override fun queueInputFrame(glObjectsProvider: GlObjectsProvider, inputTexture: GlTextureInfo, presentationTimeUs: Long) {
            val width = inputTexture.width
            val height = inputTexture.height
            if (pixels.capacity() != width * height * RGBA_BYTES) pixels = ByteBuffer.allocateDirect(width * height * RGBA_BYTES)
            pixels.clear()
            try {
                GlUtil.focusFramebufferUsingCurrentContext(inputTexture.fboId, width, height)
                GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
                GlUtil.checkGlError()
            } catch (error: GlUtil.GlException) {
                onError(VideoFrameProcessingException(error))
                return
            }
            onFrame(presentationTimeUs / US_PER_MS, Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(pixels) })
            inputListener.onInputFrameProcessed(inputTexture)
        }
    }
}

private const val RGBA_BYTES = 4
private const val US_PER_MS = 1_000L
