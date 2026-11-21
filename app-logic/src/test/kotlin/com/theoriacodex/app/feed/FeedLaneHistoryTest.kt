package com.theoriacodex.app.feed

import org.junit.Assert.assertEquals
import org.junit.Test

class FeedLaneHistoryTest {
    @Test
    fun `a card returning in another column counts as moved`() {
        val history = FeedLaneHistory()

        assertEquals(FeedLaneChanges(0, 0), history.record(listOf(FeedLanePlacement("a", 0), FeedLanePlacement("b", 1))))
        assertEquals(FeedLaneChanges(0, 0), history.record(listOf(FeedLanePlacement("c", 0))))
        assertEquals(FeedLaneChanges(1, 0), history.record(listOf(FeedLanePlacement("a", 1), FeedLanePlacement("b", 1))))
    }

    @Test
    fun `a card returning at another height counts as resized`() {
        val history = FeedLaneHistory()

        history.record(listOf(FeedLanePlacement("a", 0, heightPx = 300)))
        assertEquals(FeedLaneChanges(0, 1), history.record(listOf(FeedLanePlacement("a", 0, heightPx = 340))))
    }
}
