package com.theoriacodex.app.recommend

import com.theoriacodex.data.repository.LikeTagRewriter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LikeTagBackfillTest {
    private var done = false
    private var passes = 0
    private var failing = false
    private val rewriter = LikeTagRewriter {
        passes++
        check(!failing) { "disk full" }
        0
    }
    private val backfill = LikeTagBackfill(rewriter, isDone = { done }, markDone = { done = true })

    @Test fun `the backfill runs once and is remembered only after it finished`() = runTest {
        failing = true
        backfill.ensure()
        assertFalse(done)

        failing = false
        backfill.ensure()
        backfill.ensure()
        assertTrue(done)
        assertEquals(2, passes)
    }
}
