package com.theoriacodex.app.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerTransformStateTest {
    @Test
    fun `double tap cycles fit to 2x and back to fit`() {
        val start = ViewerTransformState()
        val zoomed = start.doubleTap()
        val fit = zoomed.doubleTap()

        assertEquals(ViewerTransformState.DOUBLE_TAP_SCALE, zoomed.zoom, 0.0001f)
        assertEquals(ViewerTransformState.FIT_SCALE, fit.zoom, 0.0001f)
        assertEquals(0f, fit.panX, 0.0001f)
        assertEquals(0f, fit.panY, 0.0001f)
    }

    @Test
    fun `pan only applies when zoom is above fit`() {
        val fit = ViewerTransformState()
        val fromFit = fit.transform(zoomChange = 1f, panChangeX = 40f, panChangeY = 25f)
        val zoomed = fit.doubleTap()
        val panned = zoomed.transform(zoomChange = 1f, panChangeX = 40f, panChangeY = 25f)

        assertEquals(0f, fromFit.panX, 0.0001f)
        assertEquals(40f, panned.panX, 0.0001f)
        assertEquals(25f, panned.panY, 0.0001f)
    }

    @Test
    fun `zoom clamps to supported range and returning to fit clears pan`() {
        val maximum = ViewerTransformState()
            .transform(zoomChange = 20f, panChangeX = 12f, panChangeY = 8f)
        val fit = maximum.transform(zoomChange = 0f, panChangeX = 5f, panChangeY = 5f)

        assertEquals(ViewerTransformState.MAX_SCALE, maximum.zoom, 0.0001f)
        assertEquals(ViewerTransformState.FIT_SCALE, fit.zoom, 0.0001f)
        assertEquals(0f, fit.panX, 0.0001f)
        assertEquals(0f, fit.panY, 0.0001f)
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
            ),
        )
        assertFalse(
            viewerMediaPagerReverseLayout(
                mediaCount = 1,
                invertMultiImageScrollDirection = true,
            ),
        )
    }
}
