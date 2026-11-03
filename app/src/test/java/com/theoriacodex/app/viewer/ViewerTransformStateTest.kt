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
    fun `double tap keeps the tapped point in place and pinching keeps the focus in place`() {
        val tapped = ViewerTransformState().doubleTap(focusX = 100f, focusY = -50f)
        assertEquals(-100f, tapped.panX, 0.0001f)
        assertEquals(50f, tapped.panY, 0.0001f)

        // A content point under the focus before the pinch must still be under it afterwards.
        val before = ViewerTransformState(zoom = 2f, panX = 30f, panY = 0f)
        val after = before.transform(1.5f, 0f, 0f, focusX = 60f, focusY = 0f)
        val contentPoint = (60f - before.panX) / before.zoom
        assertEquals(60f, after.panX + contentPoint * after.zoom, 0.001f)
    }

    @Test
    fun `pan cannot move zoomed media past its edges and fitted axes stay centred`() {
        val bounds = viewerTransformBounds(width = 400f, height = 800f, padding = 0f, aspectRatio = 1f)
        assertEquals(400f, bounds.contentHeight, 0.0001f)
        val zoomed = ViewerTransformState().doubleTap(bounds = bounds)
            .transform(1f, panChangeX = 10_000f, panChangeY = 10_000f, bounds = bounds)

        // 2x makes 800 square media: 200 of width overflows per side and height just fits.
        assertEquals(200f, zoomed.panX, 0.0001f)
        assertEquals(0f, zoomed.panY, 0.0001f)
    }

    @Test
    fun `animating between transforms interpolates zoom and pan`() {
        val halfway = ViewerTransformState().lerpTo(ViewerTransformState(zoom = 3f, panX = 40f, panY = -20f), 0.5f)
        assertEquals(ViewerTransformState(zoom = 2f, panX = 20f, panY = -10f), halfway)
    }

    @Test
    fun `multi-image scroll inversion only reverses multi-image layouts`() {
        assertTrue(viewerMediaPagerReverseLayout(mediaCount = 3, invertMultiImageScrollDirection = true))
        assertFalse(viewerMediaPagerReverseLayout(mediaCount = 1, invertMultiImageScrollDirection = true))
        assertFalse(viewerMediaPagerReverseLayout(mediaCount = 3, invertMultiImageScrollDirection = false))
    }

    @Test
    fun `flings follow their direction and slow releases need distance`() {
        fun decide(displacement: Float, velocity: Float) = viewerSwipeDecision(displacement, velocity, pageExtent = 1000f, flingVelocity = 400f)

        // A short, fast flick commits; the same distance held still does not.
        assertEquals(ViewerHorizontalSwipeDirection.Next, decide(-60f, -2000f))
        assertEquals(null, decide(-60f, 0f))
        // A slow drag commits only past 18% of the page.
        assertEquals(null, decide(-150f, -100f))
        assertEquals(ViewerHorizontalSwipeDirection.Previous, decide(200f, 0f))
        // Flicking back against a long drag cancels it.
        assertEquals(null, decide(-700f, 2000f))
    }

    @Test
    fun `edge stretch resists more the further it is pulled and keeps its direction`() {
        val near = viewerEdgeStretch(50f, 1000f)
        val far = viewerEdgeStretch(500f, 1000f)
        assertTrue(far > near)
        assertTrue(far < 180f)
        assertEquals(-near, viewerEdgeStretch(-50f, 1000f), 0.001f)
    }

    @Test
    fun `a pull commits on distance or a downward fling and a flick back cancels it`() {
        assertTrue(viewerDismissCommits(progress = 0.3f, velocityY = 0f, flingVelocity = 400f))
        assertFalse(viewerDismissCommits(progress = 0.1f, velocityY = 0f, flingVelocity = 400f))
        assertTrue(viewerDismissCommits(progress = 0.05f, velocityY = 900f, flingVelocity = 400f))
        assertFalse(viewerDismissCommits(progress = 0.9f, velocityY = -900f, flingVelocity = 400f))
    }
}
