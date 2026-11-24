package com.theoriacodex.domain.orchestration

import com.theoriacodex.domain.model.SourceKey

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
