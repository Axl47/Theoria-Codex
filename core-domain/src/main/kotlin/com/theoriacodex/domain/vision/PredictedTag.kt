package com.theoriacodex.domain.vision

/**
 * A tag the on-device tagger predicted from a post's picture, in Danbooru's spelling, with its [score] (0 to 1). It is a
 * second tag source beside the provider's tags and never one of them: nothing shows it as a tag the post was given.
 * Each use applies its own threshold ([counts]).
 */
data class PredictedTag(val tag: String, val score: Float, val kind: Kind) {
    enum class Kind { GENERAL, CHARACTER }

    /** Whether this prediction is confident enough for [use]. */
    fun counts(use: PredictedTagUse): Boolean = score >= use.threshold(kind)

    companion object {
        /** The lowest score stored: the lowest any [PredictedTagUse] needs. */
        const val STORED_FLOOR = 0.35f
    }
}

/**
 * Where predicted tags are used, and the score each needs. Thresholds come from the visual-similarity spike's
 * precision/recall against real Danbooru tags on 360 px thumbnails:
 * - [HIDE] favours recall, since a miss shows something the user asked not to see and a false hit only passes over one
 *   card: general 0.35 (precision 0.63, recall 0.55), character 0.5 (0.89 / 0.82).
 * - [EVIDENCE] seeds For You searches and ranks save suggestions, so it favours precision: general 0.5 (0.71 / 0.46),
 *   character 0.7 (0.93 / 0.78). Each tag then weighs its score.
 */
enum class PredictedTagUse(private val general: Float, private val character: Float) {
    HIDE(general = 0.35f, character = 0.5f),
    EVIDENCE(general = 0.5f, character = 0.7f),
    ;

    fun threshold(kind: PredictedTag.Kind): Float = when (kind) {
        PredictedTag.Kind.GENERAL -> general
        PredictedTag.Kind.CHARACTER -> character
    }
}
