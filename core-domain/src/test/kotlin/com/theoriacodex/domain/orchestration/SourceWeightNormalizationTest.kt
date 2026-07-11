package com.theoriacodex.domain.orchestration

import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceWeightNormalizationTest {
    @Test
    fun `normalizes positive weights while preserving explicit zero`() {
        val normalized = SourceWeightNormalization.normalize(
            sources = listOf(SourceKey.PIXIV, SourceKey.GELBOORU, SourceKey.HITOMI),
            weightsBySource = mapOf(
                SourceKey.PIXIV to 3.0,
                SourceKey.GELBOORU to 1.0,
                SourceKey.HITOMI to 0.0,
            ),
        )

        assertEquals(0.75, normalized.getValue(SourceKey.PIXIV), EPSILON)
        assertEquals(0.25, normalized.getValue(SourceKey.GELBOORU), EPSILON)
        assertEquals(0.0, normalized.getValue(SourceKey.HITOMI), 0.0)
        assertEquals(SourceWeightNormalization.NORMALIZED_TOTAL, normalized.values.sum(), EPSILON)
    }

    @Test
    fun `missing values use the declared fallback without replacing explicit zero`() {
        val normalized = SourceWeightNormalization.normalize(
            sources = listOf(SourceKey.PIXIV, SourceKey.GELBOORU),
            weightsBySource = mapOf(SourceKey.PIXIV to 0.0),
            missingWeight = 2.0,
        )

        assertEquals(0.0, normalized.getValue(SourceKey.PIXIV), 0.0)
        assertEquals(1.0, normalized.getValue(SourceKey.GELBOORU), 0.0)
    }

    @Test
    fun `invalid weights fail closed and finite positives still total one`() {
        val normalized = SourceWeightNormalization.normalize(
            sources = SourceKey.entries,
            weightsBySource = mapOf(
                SourceKey.PIXIV to Double.NaN,
                SourceKey.GELBOORU to Double.POSITIVE_INFINITY,
                SourceKey.AIBOORU to -10.0,
                SourceKey.NHENTAI to Double.MAX_VALUE,
                SourceKey.HITOMI to Double.MAX_VALUE,
            ),
            missingWeight = Double.NaN,
        )

        assertTrue(normalized.values.all(Double::isFinite))
        assertTrue(normalized.values.all { weight -> weight in 0.0..1.0 })
        assertEquals(0.5, normalized.getValue(SourceKey.NHENTAI), EPSILON)
        assertEquals(0.5, normalized.getValue(SourceKey.HITOMI), EPSILON)
        assertEquals(SourceWeightNormalization.NORMALIZED_TOTAL, normalized.values.sum(), EPSILON)
    }

    @Test
    fun `all zero input falls back to equal shares`() {
        val normalized = SourceWeightNormalization.normalize(
            sources = listOf(SourceKey.PIXIV, SourceKey.GELBOORU),
            weightsBySource = mapOf(
                SourceKey.PIXIV to 0.0,
                SourceKey.GELBOORU to -1.0,
            ),
        )

        assertEquals(
            mapOf(SourceKey.PIXIV to 0.5, SourceKey.GELBOORU to 0.5),
            normalized,
        )
        assertEquals(SourceWeightNormalization.NORMALIZED_TOTAL, normalized.values.sum(), EPSILON)
    }
}

private const val EPSILON: Double = 1e-12
