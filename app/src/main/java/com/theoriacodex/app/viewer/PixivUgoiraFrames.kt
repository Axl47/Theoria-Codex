package com.theoriacodex.app.viewer

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
            val options = BitmapFactory.Options().apply {
                inSampleSize = ugoiraFrameSampleSize(bounds.outWidth, bounds.outHeight, specs.size, sizeBucket)
            }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
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

private suspend fun readUgoiraFrame(zip: ZipFile, entry: ZipEntry): ByteArray {
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

private const val UGOIRA_INITIAL_FRAME_BATCH = 4
private const val UGOIRA_FRAME_BATCH = 8
