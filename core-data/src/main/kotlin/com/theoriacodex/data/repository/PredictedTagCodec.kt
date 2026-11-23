package com.theoriacodex.data.repository

import com.theoriacodex.domain.vision.PredictedTag

/**
 * The text form of a post's predicted tags in storage: one line per tag, `<score per mille> <g|c> <tag>`, such as
 * `873 c hatsune_miku`. Danbooru tags hold no spaces or line breaks, so the tag is the rest of the line.
 */
object PredictedTagCodec {
    private const val SCORE_STEPS = 1000

    fun encode(tags: List<PredictedTag>): String = tags.joinToString("\n") { tag ->
        val perMille = Math.round(tag.score * SCORE_STEPS).coerceIn(0, SCORE_STEPS)
        val kind = when (tag.kind) {
            PredictedTag.Kind.GENERAL -> 'g'
            PredictedTag.Kind.CHARACTER -> 'c'
        }
        "$perMille $kind ${tag.tag}"
    }

    /** The tags [encoded] holds; lines that do not read as a tag are skipped. */
    fun decode(encoded: String): List<PredictedTag> = encoded.lineSequence().mapNotNull(::decodeLine).toList()

    private fun decodeLine(line: String): PredictedTag? {
        val parts = line.split(' ', limit = 3)
        if (parts.size != 3 || parts[2].isEmpty()) return null
        val perMille = parts[0].toIntOrNull()?.takeIf { it in 0..SCORE_STEPS } ?: return null
        val kind = when (parts[1]) {
            "g" -> PredictedTag.Kind.GENERAL
            "c" -> PredictedTag.Kind.CHARACTER
            else -> return null
        }
        return PredictedTag(parts[2], perMille / SCORE_STEPS.toFloat(), kind)
    }
}
