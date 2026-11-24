package com.theoriacodex.domain.vision

import com.theoriacodex.domain.vision.PredictedTag.Kind.CHARACTER
import com.theoriacodex.domain.vision.PredictedTag.Kind.GENERAL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PredictedTagTest {
    @Test
    fun `each use counts a tag from its measured threshold and not just below it (ADR 0005)`() {
        val measured = mapOf(
            PredictedTagUse.HIDE to mapOf(GENERAL to 0.35f, CHARACTER to 0.5f),
            PredictedTagUse.EVIDENCE to mapOf(GENERAL to 0.5f, CHARACTER to 0.7f),
        )

        assertEquals(PredictedTagUse.entries.toSet(), measured.keys)
        measured.forEach { (use, thresholds) ->
            thresholds.forEach { (kind, threshold) ->
                assertTrue("$use $kind at $threshold", PredictedTag("tag", threshold, kind).counts(use))
                assertFalse("$use $kind below $threshold", PredictedTag("tag", threshold - 0.01f, kind).counts(use))
            }
        }
    }

    @Test
    fun `the stored floor is 0_35, at or below every use's threshold (ADR 0005)`() {
        val thresholds = PredictedTagUse.entries.flatMap { use -> PredictedTag.Kind.entries.map(use::threshold) }

        assertEquals(0.35f, PredictedTag.STORED_FLOOR)
        assertTrue(thresholds.isNotEmpty())
        assertTrue("floor ${PredictedTag.STORED_FLOOR} is above ${thresholds.min()}", thresholds.all { it >= PredictedTag.STORED_FLOOR })
    }
}
