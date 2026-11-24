package com.theoriacodex.app.ui.components

import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class FeedRetainedStateTest {
    @Test fun `forgetting keeps the kept feeds and their parts, and leaves keys outside the prefix alone`() {
        val retained = FeedRetainedState()
        val keys = listOf("for_you:mix:1", "for_you:mix:1:related", "for_you:mix:10", "for_you:trail:2", "search")
        val before = keys.associateWith { key -> retained.gridState(key) { LazyStaggeredGridState() } }

        retained.forgetExcept("for_you:", setOf("for_you:mix:1"))

        val after = keys.associateWith { key -> retained.gridState(key) { LazyStaggeredGridState() } }
        listOf("for_you:mix:1", "for_you:mix:1:related", "search").forEach { assertSame(before[it], after[it]) }
        listOf("for_you:mix:10", "for_you:trail:2").forEach { assertNotSame(before[it], after[it]) }
    }
}
