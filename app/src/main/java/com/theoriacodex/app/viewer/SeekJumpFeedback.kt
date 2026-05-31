package com.theoriacodex.app.viewer

import kotlin.math.abs

private const val SEEK_JUMP_FEEDBACK_VISIBLE_MS = 750L
private const val SEEK_JUMP_FEEDBACK_FADE_MS = 180L

internal const val SEEK_JUMP_FEEDBACK_LIFETIME_MS =
    SEEK_JUMP_FEEDBACK_VISIBLE_MS + SEEK_JUMP_FEEDBACK_FADE_MS

internal enum class SeekJumpDirection {
    Backward,
    Forward,
}

internal data class SeekJumpFeedback(
    val direction: SeekJumpDirection,
    val totalDeltaMs: Long,
    val serial: Int,
    val expiresAtElapsedMs: Long,
)

internal fun nextSeekJumpFeedback(
    previous: SeekJumpFeedback?,
    deltaMs: Long,
    nowElapsedMs: Long,
    nextSerial: Int,
): SeekJumpFeedback? {
    if (deltaMs == 0L) return null

    val direction = if (deltaMs > 0L) {
        SeekJumpDirection.Forward
    } else {
        SeekJumpDirection.Backward
    }
    val stackablePrevious = previous?.takeIf {
        it.direction == direction && nowElapsedMs < it.expiresAtElapsedMs
    }
    val totalDeltaMs = if (stackablePrevious != null) {
        stackablePrevious.totalDeltaMs + deltaMs
    } else {
        deltaMs
    }

    return SeekJumpFeedback(
        direction = direction,
        totalDeltaMs = totalDeltaMs,
        serial = nextSerial,
        expiresAtElapsedMs = nowElapsedMs + SEEK_JUMP_FEEDBACK_LIFETIME_MS,
    )
}

internal fun formatSeekJumpFeedback(totalDeltaMs: Long): String {
    val sign = if (totalDeltaMs >= 0L) "+" else "-"
    val seconds = abs(totalDeltaMs) / 1_000L
    return "$sign ${seconds}s"
}
