package com.theoriacodex.app.viewer

import kotlin.math.abs
import kotlin.math.roundToLong

data class EpisodeClipRange(val startMs: Long, val endMs: Long) {
    init {
        require(startMs >= 0 && endMs - startMs in EPISODE_CLIP_MIN_MS..EPISODE_CLIP_MAX_MS)
    }

    val durationMs: Long get() = endMs - startMs

    fun withStart(startMs: Long): EpisodeClipRange =
        copy(startMs = startMs.coerceIn((endMs - EPISODE_CLIP_MAX_MS).coerceAtLeast(0L), endMs - EPISODE_CLIP_MIN_MS))

    fun withEnd(endMs: Long, episodeMs: Long): EpisodeClipRange =
        copy(endMs = endMs.coerceIn(startMs + EPISODE_CLIP_MIN_MS, minOf(startMs + EPISODE_CLIP_MAX_MS, episodeMs)))
}

fun initialEpisodeClip(positionMs: Long, episodeMs: Long): EpisodeClipRange? {
    if (episodeMs < EPISODE_CLIP_MIN_MS) return null
    val length = minOf(EPISODE_CLIP_DEFAULT_MS, episodeMs)
    val start = positionMs.coerceIn(0L, episodeMs - length)
    return EpisodeClipRange(start, start + length)
}

fun episodeClipWindow(range: EpisodeClipRange, episodeMs: Long): LongRange {
    val length = minOf(EPISODE_CLIP_WINDOW_MS, episodeMs)
    val start = (range.startMs + range.durationMs / 2 - length / 2).coerceIn(0L, episodeMs - length)
    return start..start + length
}

const val EPISODE_CLIP_MAX_MS = 60_000L
const val EPISODE_CLIP_MIN_MS = 1_000L
private const val EPISODE_CLIP_DEFAULT_MS = 30_000L
private const val EPISODE_CLIP_WINDOW_MS = 120_000L

fun episodeClipTimeLabel(ms: Long): String {
    val seconds = ms.coerceAtLeast(0L) / MS_PER_SECOND
    val hours = seconds / SECONDS_PER_HOUR
    val minutes = seconds / SECONDS_PER_MINUTE % MINUTES_PER_HOUR
    val clock = "$minutes:${(seconds % SECONDS_PER_MINUTE).toString().padStart(2, '0')}"
    return if (hours > 0) "$hours:${clock.padStart(5, '0')}" else clock
}

enum class EpisodeClipHandle { START, END }

data class EpisodeClipStripScale(val window: LongRange, val widthPx: Float) {
    private val span = (window.last - window.first).coerceAtLeast(1L).toFloat()

    fun xOf(ms: Long): Float = (ms - window.first) / span * widthPx

    fun msAt(x: Float): Long = window.first + (x.coerceIn(0f, widthPx) / widthPx * span).roundToLong()

    fun handleNear(clip: EpisodeClipRange, x: Float, reachPx: Float): EpisodeClipHandle? {
        val toStart = abs(x - xOf(clip.startMs))
        val toEnd = abs(x - xOf(clip.endMs))
        return when {
            minOf(toStart, toEnd) > reachPx -> null
            toStart < toEnd -> EpisodeClipHandle.START
            else -> EpisodeClipHandle.END
        }
    }
}

val EpisodeClipRange.label: String get() = "${episodeClipTimeLabel(startMs)}–${episodeClipTimeLabel(endMs)}"

private const val MS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
private const val MINUTES_PER_HOUR = 60L
private const val SECONDS_PER_HOUR = 3_600L
