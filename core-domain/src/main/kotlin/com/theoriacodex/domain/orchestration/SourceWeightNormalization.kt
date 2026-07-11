package com.theoriacodex.domain.orchestration

import com.theoriacodex.domain.model.SourceKey

/**
 * The single serving-time contract for source priority weights.
 *
 * Explicit zero is meaningful and remains zero. Missing entries receive [missingWeight].
 * Negative and non-finite values fail closed to zero. When at least one positive weight
 * remains, positive values are scaled to [NORMALIZED_TOTAL] without overflowing their sum. A
 * degenerate all-zero input falls back to equal shares so every non-empty result still satisfies
 * the total-weight invariant and the merge scheduler cannot be starved.
 */
object SourceWeightNormalization {
    const val NORMALIZED_TOTAL: Double = 1.0

    fun normalize(
        sources: Iterable<SourceKey>,
        weightsBySource: Map<SourceKey, Double>,
        missingWeight: Double = 1.0,
    ): Map<SourceKey, Double> {
        val distinctSources = sources.distinct()
        if (distinctSources.isEmpty()) return emptyMap()

        val safeMissingWeight = missingWeight.nonNegativeFiniteOrZero()
        val sanitized = distinctSources.associateWith { source ->
            if (source in weightsBySource) {
                weightsBySource.getValue(source).nonNegativeFiniteOrZero()
            } else {
                safeMissingWeight
            }
        }
        val largestWeight = sanitized.values.maxOrNull().orZero()
        if (largestWeight <= 0.0) {
            val equalShare = NORMALIZED_TOTAL / distinctSources.size.toDouble()
            return distinctSources.associateWith { equalShare }
        }

        // Scaling by the largest value first avoids an infinite sum for very large finite inputs.
        val scaled = sanitized.mapValues { (_, weight) -> weight / largestWeight }
        val scaledTotal = scaled.values.sum()
        if (!scaledTotal.isFinite() || scaledTotal <= 0.0) return sanitized.mapValues { 0.0 }

        return scaled.mapValues { (_, weight) ->
            if (weight == 0.0) 0.0 else (weight / scaledTotal) * NORMALIZED_TOTAL
        }
    }
}

private fun Double.nonNegativeFiniteOrZero(): Double {
    return takeIf { value -> value.isFinite() && value > 0.0 } ?: 0.0
}

private fun Double?.orZero(): Double = this ?: 0.0
