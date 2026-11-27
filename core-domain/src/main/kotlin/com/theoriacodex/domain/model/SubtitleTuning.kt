package com.theoriacodex.domain.model

import kotlin.math.roundToLong

data class SubtitleTuning(val sizePercent: Int = DEFAULT_SIZE_PERCENT, val delayMs: Long = 0L) {
    init {
        require(sizePercent in MIN_SIZE_PERCENT..MAX_SIZE_PERCENT) { "Subtitle size out of range: $sizePercent" }
        require(delayMs in -MAX_DELAY_MS..MAX_DELAY_MS) { "Subtitle delay out of range: $delayMs" }
    }

    val isDefault: Boolean get() = this == SubtitleTuning()

    companion object {
        const val DEFAULT_SIZE_PERCENT = 100
        const val MIN_SIZE_PERCENT = 50
        const val MAX_SIZE_PERCENT = 200
        const val SIZE_STEP_PERCENT = 10
        const val DELAY_STEP_MS = 100L
        const val MAX_DELAY_MS = 30_000L
    }
}

sealed interface SubtitleAdjustment {
    data class Step(val steps: Int) : SubtitleAdjustment
    data object Reset : SubtitleAdjustment

    fun size(percent: Int): Int = when (this) {
        is Step -> subtitleSizePercent(percent + steps * SubtitleTuning.SIZE_STEP_PERCENT)
        Reset -> SubtitleTuning.DEFAULT_SIZE_PERCENT
    }

    fun delay(delayMs: Long): Long = when (this) {
        is Step -> subtitleDelayMs(delayMs + steps * SubtitleTuning.DELAY_STEP_MS)
        Reset -> 0L
    }
}

fun subtitleSizePercent(percent: Int): Int =
    nearestStep(percent.toLong(), SubtitleTuning.SIZE_STEP_PERCENT.toLong())
        .coerceIn(SubtitleTuning.MIN_SIZE_PERCENT.toLong(), SubtitleTuning.MAX_SIZE_PERCENT.toLong()).toInt()

fun subtitleDelayMs(delayMs: Long): Long =
    nearestStep(delayMs, SubtitleTuning.DELAY_STEP_MS).coerceIn(-SubtitleTuning.MAX_DELAY_MS, SubtitleTuning.MAX_DELAY_MS)

private fun nearestStep(value: Long, step: Long): Long = (value.toDouble() / step).roundToLong() * step
