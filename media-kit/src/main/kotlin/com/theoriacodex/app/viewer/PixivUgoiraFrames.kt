package com.theoriacodex.app.viewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal typealias UgoiraFrameDecoder = suspend (
    File, List<UgoiraFrameSpec>, UgoiraSizeBucket, suspend (UgoiraPlayback) -> Unit,
) -> UgoiraPlayback

/** Publish immutable frame batches; published bitmaps belong to consumers and must never be recycled here. */
internal suspend fun decodeUgoiraFrames(
    archive: File,
    specs: List<UgoiraFrameSpec>,
    sizeBucket: UgoiraSizeBucket,
    publish: suspend (UgoiraPlayback) -> Unit,
): UgoiraPlayback {
    val frames = ArrayList<UgoiraFrame>(specs.size)
    val delays = specs.map(UgoiraFrameSpec::delayMs)
    val budget = ugoiraPlaybackBudget(sizeBucket)
    var decodedBytes = 0L
    ZipFile(archive).use { zip ->
        specs.forEach { spec ->
            currentCoroutineContext().ensureActive()
            val entry = zip.getEntry(spec.fileName) ?: throw IOException("Pixiv ugoira frame is missing")
            val bytes = readUgoiraFrame(zip, entry)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val sampleSize = ugoiraFrameSampleSize(bounds.outWidth, bounds.outHeight, specs.size, sizeBucket)
            val bitmap = decodeUgoiraFrame(bytes, sampleSize, sizeBucket)
                ?: throw IOException("Pixiv ugoira frame could not be decoded")
            decodedBytes += bitmap.allocationByteCount.toLong()
            if (decodedBytes > budget) {
                bitmap.recycle()
                throw IOException("Pixiv ugoira decoded frames exceed memory limit")
            }
            frames += UgoiraFrame(bitmap, spec.delayMs)
            if (frames.size == UGOIRA_INITIAL_FRAME_BATCH || frames.size % UGOIRA_FRAME_BATCH == 0) {
                currentCoroutineContext().ensureActive()
                publish(UgoiraPlayback(frames.toList(), delays))
            }
        }
    }
    currentCoroutineContext().ensureActive()
    if (frames.isEmpty()) throw IOException("Pixiv ugoira zip could not be decoded")
    return UgoiraPlayback(frames.toList(), delays)
}

/**
 * Display frames decode straight to [Bitmap.Config.HARDWARE], so showing a new frame each tick needs no
 * RenderThread texture upload. Export reads pixels with `getPixels`, so its own decode stays software.
 * A hardware decode that fails (for example when graphics memory is short) falls back to software.
 */
private fun decodeUgoiraFrame(bytes: ByteArray, sampleSize: Int, sizeBucket: UgoiraSizeBucket): Bitmap? {
    fun decode(config: Bitmap.Config) = BitmapFactory.decodeByteArray(
        bytes, 0, bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sampleSize; inPreferredConfig = config },
    )
    if (sizeBucket == UgoiraSizeBucket.EXPORT) return decode(Bitmap.Config.ARGB_8888)
    return decode(Bitmap.Config.HARDWARE) ?: decode(Bitmap.Config.ARGB_8888)
}

internal suspend fun readUgoiraFrame(zip: ZipFile, entry: ZipEntry): ByteArray {
    return zip.getInputStream(entry).use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size().toLong() + count > UGOIRA_MAX_FRAME_EXPANDED_BYTES) {
                throw IOException("Pixiv ugoira frame exceeds expanded-byte limit")
            }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
}

/**
 * Up to [count] of [specs]' frames, spread evenly through the animation, each decoded from [archive] with power-of-two
 * sampling to no less than [sizePx] on its long edge. For the picture tagger, which reads a few frames, not playback.
 */
internal suspend fun sampleUgoiraFrames(archive: File, specs: List<UgoiraFrameSpec>, count: Int, sizePx: Int): List<Bitmap> {
    if (specs.isEmpty() || count <= 0) return emptyList()
    val picked = List(minOf(count, specs.size)) { index -> specs[((index + 0.5) * specs.size / minOf(count, specs.size)).toInt()] }
    return ZipFile(archive).use { zip ->
        picked.map { spec ->
            val entry = zip.getEntry(spec.fileName) ?: throw IOException("Pixiv ugoira frame is missing")
            val bytes = readUgoiraFrame(zip, entry)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= sizePx) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: throw IOException("Pixiv ugoira frame could not be decoded")
        }
    }
}

private const val UGOIRA_INITIAL_FRAME_BATCH = 1
private const val UGOIRA_FRAME_BATCH = 8
