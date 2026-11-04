package com.theoriacodex.app.feed

import org.junit.Assert.assertEquals
import org.junit.Test

class FeedLaneHistoryTest {
    @Test
    fun `a card returning in another column counts as moved`() {
        val history = FeedLaneHistory()

        assertEquals(0, history.record(listOf(FeedLanePlacement("a", 0), FeedLanePlacement("b", 1))))
        assertEquals(0, history.record(listOf(FeedLanePlacement("c", 0))))
        assertEquals(1, history.record(listOf(FeedLanePlacement("a", 1), FeedLanePlacement("b", 1))))
    }
}
