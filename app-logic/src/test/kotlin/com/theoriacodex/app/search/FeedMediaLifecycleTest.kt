package com.theoriacodex.app.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedMediaLifecycleTest {
    @Test
    fun `never-visible card cannot acquire a player lease`() {
        assertFalse(
            shouldAcquireFeedPlayerLease(
                isActive = false,
                stableVisibilityElapsed = true,
            ),
        )
    }

    @Test
    fun `stable visibility is required before acquiring a player lease`() {
        assertFalse(
            shouldAcquireFeedPlayerLease(
                isActive = true,
                stableVisibilityElapsed = false,
            ),
        )
        assertTrue(
            shouldAcquireFeedPlayerLease(
                isActive = true,
                stableVisibilityElapsed = true,
            ),
        )
        assertEquals(180L, FEED_PLAYER_ACTIVATION_DELAY_MS)
    }

    @Test
    fun `lifecycle stop releases eligibility even after the visibility delay`() {
        assertFalse(
            shouldAcquireFeedPlayerLease(
                isActive = false,
                stableVisibilityElapsed = true,
            ),
        )
    }

    @Test
    fun `every visible sibling remains independently active`() {
        val visibleCards = List(4) {
            shouldFeedMediaPlay(isInViewport = true, isLifecycleStarted = true)
        }
        assertTrue(visibleCards.all { it })

        val afterOneLeavesViewport = listOf(true, true, false, true).map { visible ->
            shouldFeedMediaPlay(isInViewport = visible, isLifecycleStarted = true)
        }
        assertEquals(listOf(true, true, false, true), afterOneLeavesViewport)

        val afterLifecycleStop = List(4) { visible ->
            shouldFeedMediaPlay(isInViewport = visible >= 0, isLifecycleStarted = false)
        }
        assertTrue(afterLifecycleStop.none { it })
    }

    @Test
    fun `clipped bounds are inactive while any visible area remains active`() {
        assertFalse(hasVisibleFeedArea(0f, 0f))
        assertFalse(hasVisibleFeedArea(20f, 0f))
        assertTrue(hasVisibleFeedArea(1f, 1f))
    }

    @Test
    fun `feed decode size follows card geometry with explicit ceilings`() {
        assertEquals(
            FeedPreviewDecodeSize(widthPx = 540, heightPx = 960),
            feedPreviewDecodeSize(screenWidthPx = 1_080, aspectRatio = 0.5625f),
        )
        assertEquals(
            FeedPreviewDecodeSize(widthPx = 1_600, heightPx = 2_400),
            feedPreviewDecodeSize(screenWidthPx = 8_000, aspectRatio = 0.1f),
        )
        assertEquals(
            FeedPreviewDecodeSize(widthPx = 500, heightPx = 500),
            feedPreviewDecodeSize(screenWidthPx = 1_000, aspectRatio = Float.NaN),
        )
    }
}
