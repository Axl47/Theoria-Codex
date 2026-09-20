package com.theoriacodex.app.viewer

import java.io.IOException

enum class UgoiraSizeBucket(
    val maxDimension: Int,
) {
    CARD(720),
    VIEWER(2_048),
    EXPORT(Int.MAX_VALUE),
}

fun ugoiraPlaybackBudget(bucket: UgoiraSizeBucket): Long = when (bucket) {
    UgoiraSizeBucket.CARD -> 16L * 1024L * 1024L
    UgoiraSizeBucket.VIEWER -> 64L * 1024L * 1024L
    UgoiraSizeBucket.EXPORT -> UGOIRA_MAX_DECODED_PLAYBACK_BYTES
}

/** Long animations trade preview resolution for a bounded complete playback, preserving export quality. */
fun ugoiraFrameSampleSize(width: Int, height: Int, frameCount: Int, bucket: UgoiraSizeBucket): Int {
    if (width <= 0 || height <= 0 || width > UGOIRA_MAX_FRAME_DIMENSION || height > UGOIRA_MAX_FRAME_DIMENSION ||
        width.toLong() * height > UGOIRA_MAX_FRAME_PIXELS || frameCount !in 1..UGOIRA_MAX_FRAME_COUNT
    ) throw IOException("Pixiv ugoira frame dimensions exceed supported limits")
    if (bucket == UgoiraSizeBucket.EXPORT) return 1
    val frameBudget = ugoiraPlaybackBudget(bucket) / frameCount
    var sample = 1
    while (true) {
        val sampledWidth = (width + sample - 1) / sample
        val sampledHeight = (height + sample - 1) / sample
        if (maxOf(sampledWidth, sampledHeight) <= bucket.maxDimension &&
            sampledWidth.toLong() * sampledHeight * 4L <= frameBudget
        ) return sample
        sample *= 2
    }
}

const val UGOIRA_MAX_DECODED_PLAYBACK_BYTES = 192L * 1024L * 1024L
const val UGOIRA_MAX_FRAME_COUNT = 400
private const val UGOIRA_MAX_FRAME_DIMENSION = 8_192
private const val UGOIRA_MAX_FRAME_PIXELS = 40_000_000L
