package com.theoriacodex.app.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerStateTest {
    @Test
    fun `double tap cycles fit to 2x and back to fit`() {
        val start = ViewerState(streamSize = 5)
        val zoomed = start.doubleTap()
        val fit = zoomed.doubleTap()

        assertEquals(ViewerState.DOUBLE_TAP_SCALE, zoomed.zoom, 0.0001f)
        assertEquals(ViewerState.FIT_SCALE, fit.zoom, 0.0001f)
        assertEquals(0f, fit.panX, 0.0001f)
    }

    @Test
    fun `pan only applies when zoom is above fit`() {
        val fit = ViewerState(streamSize = 3)
        val fromFit = fit.transform(zoomChange = 1f, panChangeX = 40f, panChangeY = 25f)
        val zoomed = fit.doubleTap()
        val panned = zoomed.transform(zoomChange = 1f, panChangeX = 40f, panChangeY = 25f)

        assertEquals(0f, fromFit.panX, 0.0001f)
        assertEquals(40f, panned.panX, 0.0001f)
        assertEquals(25f, panned.panY, 0.0001f)
    }

    @Test
    fun `index changes reset transform and respect bounds`() {
        val state = ViewerState(streamSize = 2)
            .doubleTap()
            .transform(zoomChange = 1f, panChangeX = 20f, panChangeY = 10f)

        val next = state.next()
        val clamped = next.next()
        val previous = clamped.previous()

        assertEquals(1, next.currentIndex)
        assertEquals(ViewerState.FIT_SCALE, next.zoom, 0.0001f)
        assertEquals(1, clamped.currentIndex)
        assertEquals(0, previous.currentIndex)
    }

    @Test
    fun `chrome toggle and hide transitions`() {
        val state = ViewerState(streamSize = 1)
        val toggled = state.toggleChrome()
        val hidden = toggled.hideChrome()

        assertFalse(toggled.chromeVisible)
        assertFalse(hidden.chromeVisible)
        assertTrue(state.chromeVisible)
    }

    @Test
    fun `multi-image scroll inversion flips only multi-image swipe direction`() {
        assertEquals(
            ViewerHorizontalSwipeDirection.Previous,
            viewerSwipeDirectionForSetting(
                rawDirection = ViewerHorizontalSwipeDirection.Next,
                currentMediaCount = 3,
                invertMultiImageScrollDirection = true,
            ),
        )
        assertEquals(
            ViewerHorizontalSwipeDirection.Next,
            viewerSwipeDirectionForSetting(
                rawDirection = ViewerHorizontalSwipeDirection.Next,
                currentMediaCount = 1,
                invertMultiImageScrollDirection = true,
            ),
        )
        assertEquals(
            ViewerHorizontalSwipeDirection.Next,
            viewerSwipeDirectionForSetting(
                rawDirection = ViewerHorizontalSwipeDirection.Next,
                currentMediaCount = 3,
                invertMultiImageScrollDirection = false,
            ),
        )
        assertTrue(
            viewerMediaPagerReverseLayout(
                mediaCount = 3,
                invertMultiImageScrollDirection = true,
            )
        )
        assertFalse(
            viewerMediaPagerReverseLayout(
                mediaCount = 1,
                invertMultiImageScrollDirection = true,
            )
        )
    }
}
