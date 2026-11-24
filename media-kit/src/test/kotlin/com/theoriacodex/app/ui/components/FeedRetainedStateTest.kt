package com.theoriacodex.app.ui.components

import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class FeedRetainedStateTest {
    @Test fun `forgetting keeps the kept feeds and their parts in every retained map, and leaves keys outside the prefix alone (ADR 0106)`() {
        val retained = FeedRetainedState()
        val keys = listOf("for_you:mix:1", "for_you:mix:1:related", "for_you:mix:10", "for_you:trail:2", "search")
        fun snapshot() = keys.associateWith { key ->
            listOf(retained.gridState(key) { LazyStaggeredGridState() }, retained.durationProjection(key), retained.frameCache<String, String>(key))
        }
        val before = snapshot()

        retained.forgetExcept("for_you:", setOf("for_you:mix:1"))

        val after = snapshot()
        listOf("for_you:mix:1", "for_you:mix:1:related", "search").forEach { key ->
            before.getValue(key).zip(after.getValue(key)).forEach { (old, new) -> assertSame(old, new) }
        }
        listOf("for_you:mix:10", "for_you:trail:2").forEach { key ->
            before.getValue(key).zip(after.getValue(key)).forEach { (old, new) -> assertNotSame(old, new) }
        }
    }
}
