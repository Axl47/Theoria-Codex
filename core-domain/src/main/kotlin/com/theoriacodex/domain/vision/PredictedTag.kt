package com.theoriacodex.domain.vision

import com.theoriacodex.domain.tags.normalizeMatchToken

data class PredictedTag(val tag: String, val score: Float, val kind: Kind) {
    enum class Kind { GENERAL, CHARACTER }

    fun counts(use: PredictedTagUse): Boolean = score >= use.threshold(kind)

    companion object {
        const val STORED_FLOOR = 0.35f
    }
}

enum class PredictedTagUse(private val general: Float, private val character: Float) {
    HIDE(general = 0.35f, character = 0.5f),
    EVIDENCE(general = 0.5f, character = 0.7f),
    ;

    fun threshold(kind: PredictedTag.Kind): Float = when (kind) {
        PredictedTag.Kind.GENERAL -> general
        PredictedTag.Kind.CHARACTER -> character
    }
}

fun List<PredictedTag>.tokens(use: PredictedTagUse): Map<String, Double> =
    filter { it.counts(use) }.associate { normalizeMatchToken(it.tag) to it.score.toDouble() }
