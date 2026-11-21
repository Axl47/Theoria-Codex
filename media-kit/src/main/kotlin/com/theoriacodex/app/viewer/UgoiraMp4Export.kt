package com.theoriacodex.app.viewer

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.core.graphics.scale
import java.io.FileDescriptor
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

/** Encodes [playback] as H.264 MP4 at a fixed frame rate, repeating frames to keep each one's delay. */
internal fun encodeUgoiraMp4(
    playback: UgoiraPlayback,
    outputFileDescriptor: FileDescriptor,
) {
    val firstBitmap = playback.frames.firstOrNull()?.bitmap
        ?: throw IOException("Pixiv ugoira has no frames")

    val width = normalizedVideoDimension(firstBitmap.width)
    val height = normalizedVideoDimension(firstBitmap.height)
    val frameDurationUs = 1_000_000L / UGOIRA_EXPORT_FPS
    val bitRate = (width * height * UGOIRA_BITRATE_PER_PIXEL)
        .coerceIn(UGOIRA_MIN_BITRATE, UGOIRA_MAX_BITRATE)

    val mediaFormat = MediaFormat.createVideoFormat(UGOIRA_MP4_MIME_TYPE, width, height).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
        setInteger(MediaFormat.KEY_FRAME_RATE, UGOIRA_EXPORT_FPS)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
    }

    val codec = MediaCodec.createEncoderByType(UGOIRA_MP4_MIME_TYPE)
    val muxer = MediaMuxer(outputFileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    val bufferInfo = MediaCodec.BufferInfo()
    val output = UgoiraMuxerOutput(muxer)

    try {
        codec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val presentationTimeUs = encodeFrames(playback, codec, width, height, frameDurationUs) {
            drainEncoder(codec, bufferInfo, output::onFormatChanged, output::onEncodedSample)
        }

        val eosIndex = awaitInputBuffer(codec)
        codec.queueInputBuffer(
            eosIndex,
            0,
            0,
            presentationTimeUs,
            MediaCodec.BUFFER_FLAG_END_OF_STREAM,
        )

        var reachedEnd = false
        while (!reachedEnd) {
            reachedEnd = drainEncoder(
                codec = codec,
                bufferInfo = bufferInfo,
                onFormatChanged = output::onFormatChanged,
                onEncodedSample = output::onEncodedSample,
            )
        }
    } finally {
        runCatching { codec.stop() }
        codec.release()

        if (output.started) {
            runCatching { muxer.stop() }
        }
        muxer.release()
    }
}

private fun encodeFrames(
    playback: UgoiraPlayback,
    codec: MediaCodec,
    width: Int,
    height: Int,
    frameDurationUs: Long,
    drain: () -> Unit,
): Long {
    var presentationTimeUs = 0L
    // One pixel buffer for the whole export; each frame is read once, however often it repeats.
    val argb = IntArray(width * height)
    playback.frames.forEach { frame ->
        val sizedBitmap = if (frame.bitmap.width != width || frame.bitmap.height != height) {
            frame.bitmap.scale(width, height, filter = true)
        } else {
            frame.bitmap
        }
        try {
            sizedBitmap.getPixels(argb, 0, width, 0, 0, width, height)
            val repeats = delayToFrameRepeat(frame.delayMs)
            repeat(repeats) {
                val inputIndex = awaitInputBuffer(codec)
                val inputImage = codec.getInputImage(inputIndex)
                    ?: throw IOException("Video encoder did not return input image")
                inputImage.use {
                    writeArgbToImage(
                        argb = argb,
                        image = it,
                        width = width,
                        height = height,
                    )
                }
                codec.queueInputBuffer(
                    inputIndex,
                    0,
                    width * height * 3 / 2,
                    presentationTimeUs,
                    0,
                )

                drain()

                presentationTimeUs += frameDurationUs
            }
        } finally {
            if (sizedBitmap !== frame.bitmap) {
                sizedBitmap.recycle()
            }
        }
    }
    return presentationTimeUs
}

private fun drainEncoder(
    codec: MediaCodec,
    bufferInfo: MediaCodec.BufferInfo,
    onFormatChanged: (MediaFormat) -> Unit,
    onEncodedSample: (java.nio.ByteBuffer, MediaCodec.BufferInfo) -> Unit,
): Boolean {
    while (true) {
        val outputIndex = codec.dequeueOutputBuffer(bufferInfo, UGOIRA_CODEC_TIMEOUT_US)
        when {
            outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
            outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                onFormatChanged(codec.outputFormat)
            }
            outputIndex >= 0 -> {
                val encodedData = codec.getOutputBuffer(outputIndex)
                    ?: throw IOException("Video encoder returned null output buffer")
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    bufferInfo.size = 0
                }
                if (bufferInfo.size > 0) {
                    onEncodedSample(encodedData, bufferInfo)
                }
                val reachedEnd = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                codec.releaseOutputBuffer(outputIndex, false)
                if (reachedEnd) {
                    return true
                }
            }
        }
    }
}

private fun writeArgbToImage(
    argb: IntArray,
    image: Image,
    width: Int,
    height: Int,
) {
    if (image.planes.size < 3) {
        throw IOException("Video encoder input image is missing YUV planes")
    }

    val yPlane = image.planes[0]
    val uPlane = image.planes[1]
    val vPlane = image.planes[2]

    val yBuffer = yPlane.buffer
    val uBuffer = uPlane.buffer
    val vBuffer = vPlane.buffer

    for (y in 0 until height) {
        val yRowOffset = y * yPlane.rowStride
        val uvRow = y / 2
        val uvRowOffsetU = uvRow * uPlane.rowStride
        val uvRowOffsetV = uvRow * vPlane.rowStride

        for (x in 0 until width) {
            val color = argb[(y * width) + x]
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF

            val yValue = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
            val uValue = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
            val vValue = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128

            yBuffer.put(
                yRowOffset + (x * yPlane.pixelStride),
                yValue.coerceIn(0, 255).toByte(),
            )

            if (y % 2 == 0 && x % 2 == 0) {
                val uvColumn = x / 2
                uBuffer.put(
                    uvRowOffsetU + (uvColumn * uPlane.pixelStride),
                    uValue.coerceIn(0, 255).toByte(),
                )
                vBuffer.put(
                    uvRowOffsetV + (uvColumn * vPlane.pixelStride),
                    vValue.coerceIn(0, 255).toByte(),
                )
            }
        }
    }
}

private fun awaitInputBuffer(codec: MediaCodec): Int {
    while (true) {
        val inputIndex = codec.dequeueInputBuffer(UGOIRA_CODEC_TIMEOUT_US)
        if (inputIndex >= 0) {
            return inputIndex
        }
    }
}

private fun normalizedVideoDimension(input: Int): Int {
    val candidate = if (input % 2 == 0) input else input - 1
    return max(candidate, 2)
}

private fun delayToFrameRepeat(delayMs: Int): Int {
    val normalizedDelay = delayMs.coerceAtLeast(UGOIRA_MIN_DELAY_MS)
    return ((normalizedDelay.toFloat() * UGOIRA_EXPORT_FPS.toFloat()) / 1000f)
        .roundToInt()
        .coerceAtLeast(1)
}

/** The export's file name: the post's sanitized title, or `pixiv_<id>`, then the id. */
internal fun ugoiraMp4FileName(postId: String, title: String?): String {
    val normalizedTitle = title
        ?.sanitizeFileComponent()
        ?.takeIf { it.isNotBlank() }
        ?: "pixiv_$postId"
    return "${normalizedTitle}_$postId.mp4"
}

private fun String.sanitizeFileComponent(): String {
    val cleaned = trim()
        .replace(Regex("[^A-Za-z0-9._-]+"), "_")
        .trim('_')
    return cleaned.ifBlank { "pixiv" }
}

private class UgoiraMuxerOutput(private val muxer: MediaMuxer) {
    var started = false
        private set
    private var trackIndex = -1

    fun onFormatChanged(outputFormat: MediaFormat) {
        if (!started) {
            trackIndex = muxer.addTrack(outputFormat)
            muxer.start()
            started = true
        }
    }

    fun onEncodedSample(encodedData: java.nio.ByteBuffer, info: MediaCodec.BufferInfo) {
        if (!started || trackIndex < 0) return
        if (info.size <= 0) return
        encodedData.position(info.offset)
        encodedData.limit(info.offset + info.size)
        muxer.writeSampleData(trackIndex, encodedData, info)
    }
}

private const val UGOIRA_MP4_MIME_TYPE = "video/avc"
private const val UGOIRA_EXPORT_FPS = 30
private const val UGOIRA_CODEC_TIMEOUT_US = 10_000L
private const val UGOIRA_MIN_DELAY_MS = 16
private const val UGOIRA_BITRATE_PER_PIXEL = 6
private const val UGOIRA_MIN_BITRATE = 900_000
private const val UGOIRA_MAX_BITRATE = 12_000_000
