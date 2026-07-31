package com.theoriacodex.app.search

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedMediaLifecycleTest {
    @Test
    fun `never-visible card performs no prepare`() {
        val state = FeedPlayerActivationState()

        repeat(4) {
            val decision = state.update(isActive = false)
            assertFalse(decision.shouldPrepare)
            assertFalse(decision.shouldRetainPlayer)
            assertFalse(decision.shouldPlay)
        }
    }

    @Test
    fun `first visibility prepares once and retained visibility changes do not reprepare`() {
        val state = FeedPlayerActivationState()

        val firstVisible = state.update(isActive = true)
        val recomposedVisible = state.update(isActive = true)
        val offscreen = state.update(isActive = false)
        val visibleAgain = state.update(isActive = true)

        assertTrue(firstVisible.shouldPrepare)
        assertEquals(
            1,
            listOf(firstVisible, recomposedVisible, offscreen, visibleAgain).count { it.shouldPrepare },
        )
        assertTrue(offscreen.shouldRetainPlayer)
        assertFalse(offscreen.shouldPlay)
        assertTrue(visibleAgain.shouldPlay)
    }

    @Test
    fun `lifecycle stop and start toggle playback without another prepare`() {
        val state = FeedPlayerActivationState()

        val started = state.update(isActive = true)
        val stopped = state.update(isActive = false)
        val restarted = state.update(isActive = true)

        assertTrue(started.shouldPrepare)
        assertFalse(stopped.shouldPlay)
        assertFalse(restarted.shouldPrepare)
        assertTrue(restarted.shouldPlay)
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
        assertFalse(isVisibleFeedBounds(Rect.Zero))
        assertFalse(isVisibleFeedBounds(Rect(0f, 0f, 20f, 0f)))
        assertTrue(isVisibleFeedBounds(Rect(0f, 0f, 1f, 1f)))
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
