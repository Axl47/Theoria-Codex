package com.theoriacodex.app.media

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import coil.ImageLoader
import coil.decode.DecodeResult
import coil.decode.Decoder
import coil.decode.ImageSource
import coil.fetch.SourceResult
import coil.size.Dimension
import coil.size.Scale
import coil.request.Options
import com.github.penfeizhou.animation.loader.ByteBufferLoader
import com.github.penfeizhou.animation.webp.WebPDrawable
import java.io.IOException
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okio.Buffer
import okio.BufferedSource
import kotlin.math.roundToInt

/**
 * Animated WebP fallback for Android 8.x and static first-frame decoder for Media Overview tiles.
 */
internal class LegacyAnimatedWebPDecoder(
    private val source: ImageSource,
    private val options: Options,
    private val mode: AnimatedWebPDecodeMode,
    private val declaredHeader: AnimatedWebPHeader,
) : Decoder {
    override suspend fun decode(): DecodeResult = runInterruptible(Dispatchers.IO) {
        val bytes = readBoundedAnimatedWebPBytesBlocking(source.source())
        val header = parseAnimatedWebPHeader(bytes)
            ?: throw IOException("Animated WebP header changed before decode")
        if (header != declaredHeader) {
            throw IOException("Animated WebP dimensions changed before decode")
        }
        validateAnimatedWebPCanvas(header)

        val webPDrawable = WebPDrawable(animatedWebPByteBufferLoader(bytes))
        val intrinsicWidth = webPDrawable.intrinsicWidth
        val intrinsicHeight = webPDrawable.intrinsicHeight
        if (intrinsicWidth != header.width || intrinsicHeight != header.height) {
            webPDrawable.stop()
            throw IOException(
                "Animated WebP decoded ${intrinsicWidth}x$intrinsicHeight instead of " +
                    "${header.width}x${header.height}",
            )
        }

        var isSampled = false
        val drawable = when (mode) {
            AnimatedWebPDecodeMode.ANIMATED,
            AnimatedWebPDecodeMode.CONTROLLABLE,
            -> webPDrawable
            AnimatedWebPDecodeMode.STATIC_FIRST_FRAME -> {
                val frame = try {
                    webPDrawable.frameSeqDecoder.getFrameBitmap(0)
                        ?: throw IOException("Animated WebP did not decode a first frame")
                } finally {
                    webPDrawable.stop()
                }
                try {
                    val scaledFrame = frame.scaledToward(options)
                    if (scaledFrame !== frame) {
                        frame.recycle()
                        isSampled = true
                    }
                    BitmapDrawable(options.context.resources, scaledFrame)
                } catch (error: Throwable) {
                    if (!frame.isRecycled) frame.recycle()
                    throw error
                }
            }
        }
        DecodeResult(
            drawable = drawable,
            isSampled = isSampled,
        )
    }

    class Factory : Decoder.Factory {
        override fun create(
            result: SourceResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder? {
            val requestedMode = options.parameters.value<AnimatedWebPDecodeMode>(
                ANIMATED_WEBP_DECODE_MODE_PARAMETER,
            )
            val mode = requestedMode ?: AnimatedWebPDecodeMode.ANIMATED
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                mode == AnimatedWebPDecodeMode.ANIMATED
            ) {
                return null
            }
            val header = result.source.source().animatedWebPHeaderOrNull() ?: return null
            return LegacyAnimatedWebPDecoder(
                source = result.source,
                options = options,
                mode = mode,
                declaredHeader = header,
            )
        }
    }
}

internal enum class AnimatedWebPDecodeMode {
    ANIMATED,
    STATIC_FIRST_FRAME,
    CONTROLLABLE,
    ;

    val memoryCacheKey: String
        get() = when (this) {
            ANIMATED -> "animated-webp-v1"
            STATIC_FIRST_FRAME -> STATIC_ANIMATED_WEBP_MEMORY_CACHE_KEY
            CONTROLLABLE -> "controllable-animated-webp-v1"
        }
}

internal data class AnimatedWebPHeader(
    val width: Int,
    val height: Int,
) {
    val decodedArgbBytes: Long
        get() = width.toLong() * height.toLong() * ARGB_BYTES_PER_PIXEL
}

internal fun animatedWebPByteBufferLoader(bytes: ByteArray): ByteBufferLoader {
    return object : ByteBufferLoader() {
        override fun getByteBuffer(): ByteBuffer {
            return ByteBuffer.wrap(bytes).asReadOnlyBuffer()
        }
    }
}

internal fun isAnimatedWebPHeader(bytes: ByteArray): Boolean {
    return parseAnimatedWebPHeader(bytes) != null
}

internal fun parseAnimatedWebPHeader(bytes: ByteArray): AnimatedWebPHeader? {
    if (bytes.size < WEBP_EXTENDED_HEADER_SIZE) return null
    val isAnimated = bytes.matchesAscii(offset = 0, value = "RIFF") &&
        bytes.matchesAscii(offset = 8, value = "WEBP") &&
        bytes.matchesAscii(offset = 12, value = "VP8X") &&
        (bytes[WEBP_FEATURE_FLAGS_OFFSET].toInt() and WEBP_ANIMATION_FLAG) != 0
    if (!isAnimated) return null
    return AnimatedWebPHeader(
        width = bytes.readUnsignedInt24LittleEndian(WEBP_CANVAS_WIDTH_OFFSET) + 1,
        height = bytes.readUnsignedInt24LittleEndian(WEBP_CANVAS_HEIGHT_OFFSET) + 1,
    )
}

internal fun validateAnimatedWebPCanvas(header: AnimatedWebPHeader) {
    if (header.decodedArgbBytes > MAX_ANIMATED_WEBP_DECODED_ARGB_BYTES) {
        throw IOException(
            "Animated WebP ${header.width}x${header.height} needs " +
                "${header.decodedArgbBytes} decoded ARGB bytes; limit is " +
                "$MAX_ANIMATED_WEBP_DECODED_ARGB_BYTES",
        )
    }
}

internal suspend fun readBoundedAnimatedWebPBytes(
    source: BufferedSource,
    maxBytes: Long = MAX_ANIMATED_WEBP_BYTES,
): ByteArray = runInterruptible(Dispatchers.IO) {
    readBoundedAnimatedWebPBytesBlocking(source, maxBytes)
}

private fun readBoundedAnimatedWebPBytesBlocking(
    source: BufferedSource,
    maxBytes: Long = MAX_ANIMATED_WEBP_BYTES,
): ByteArray {
    require(maxBytes >= 0L) { "maxBytes must not be negative" }
    val buffer = Buffer()
    var totalBytes = 0L
    while (true) {
        val remainingBytes = maxBytes - totalBytes
        val readSize = if (remainingBytes >= ANIMATED_WEBP_READ_CHUNK_BYTES) {
            ANIMATED_WEBP_READ_CHUNK_BYTES
        } else {
            remainingBytes + 1L
        }
        val read = source.read(buffer, readSize)
        if (read == -1L) return buffer.readByteArray()
        totalBytes += read
        if (totalBytes > maxBytes) {
            throw IOException("Animated WebP exceeded the ${maxBytes}-byte decode limit")
        }
    }
}

private fun BufferedSource.animatedWebPHeaderOrNull(): AnimatedWebPHeader? {
    if (!request(WEBP_EXTENDED_HEADER_SIZE.toLong())) return null
    return parseAnimatedWebPHeader(peek().readByteArray(WEBP_EXTENDED_HEADER_SIZE.toLong()))
}

private fun ByteArray.matchesAscii(offset: Int, value: String): Boolean {
    if (offset < 0 || offset + value.length > size) return false
    return value.indices.all { index ->
        this[offset + index].toInt() == value[index].code
    }
}

private fun ByteArray.readUnsignedInt24LittleEndian(offset: Int): Int {
    return (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16)
}

private fun Bitmap.scaledToward(options: Options): Bitmap {
    val targetWidth = (options.size.width as? Dimension.Pixels)?.px ?: return this
    val targetHeight = (options.size.height as? Dimension.Pixels)?.px ?: return this
    if (targetWidth <= 0 || targetHeight <= 0) return this
    val widthScale = targetWidth.toFloat() / width.toFloat()
    val heightScale = targetHeight.toFloat() / height.toFloat()
    val scale = when (options.scale) {
        Scale.FILL -> maxOf(widthScale, heightScale)
        Scale.FIT -> minOf(widthScale, heightScale)
    }.coerceAtMost(1f)
    if (scale >= 1f) return this
    val scaledWidth = (width * scale).roundToInt().coerceAtLeast(1)
    val scaledHeight = (height * scale).roundToInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(this, scaledWidth, scaledHeight, true)
}

internal const val MAX_ANIMATED_WEBP_BYTES = 64L * 1024L * 1024L
internal const val MAX_ANIMATED_WEBP_DECODED_ARGB_BYTES = 32L * 1024L * 1024L
internal const val ANIMATED_WEBP_DECODE_MODE_PARAMETER =
    "com.theoriacodex.app.media.animatedWebPDecodeMode"
internal const val STATIC_ANIMATED_WEBP_MEMORY_CACHE_KEY = "static-first-frame-v1"
private const val ANIMATED_WEBP_READ_CHUNK_BYTES = 8L * 1024L
private const val WEBP_EXTENDED_HEADER_SIZE = 30
private const val WEBP_FEATURE_FLAGS_OFFSET = 20
private const val WEBP_CANVAS_WIDTH_OFFSET = 24
private const val WEBP_CANVAS_HEIGHT_OFFSET = 27
private const val WEBP_ANIMATION_FLAG = 0x02
private const val ARGB_BYTES_PER_PIXEL = 4L
