package com.theoriacodex.domain.vision

import com.theoriacodex.domain.vision.PredictedTag.Kind.CHARACTER
import com.theoriacodex.domain.vision.PredictedTag.Kind.GENERAL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PredictedTagTest {
    @Test
    fun `each use applies its own measured threshold (ADR 0005)`() {
        assertEquals(
            mapOf(
                PredictedTagUse.HIDE to listOf(0.35f, 0.5f),
                PredictedTagUse.EVIDENCE to listOf(0.5f, 0.7f),
            ),
            PredictedTagUse.entries.associateWith { use -> listOf(use.threshold(GENERAL), use.threshold(CHARACTER)) },
        )
    }

    @Test
    fun `the stored floor is at or below every use's threshold (ADR 0005)`() {
        val thresholds = PredictedTagUse.entries.flatMap { use -> PredictedTag.Kind.entries.map(use::threshold) }

        assertTrue(thresholds.isNotEmpty())
        assertTrue("floor ${PredictedTag.STORED_FLOOR} is above ${thresholds.min()}", thresholds.all { it >= PredictedTag.STORED_FLOOR })
    }
}
