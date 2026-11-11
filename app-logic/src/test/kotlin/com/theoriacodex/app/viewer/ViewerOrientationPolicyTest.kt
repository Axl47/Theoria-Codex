package com.theoriacodex.app.viewer

import com.theoriacodex.app.viewer.ViewerOrientation.LANDSCAPE
import com.theoriacodex.app.viewer.ViewerOrientation.PORTRAIT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerOrientationPolicyTest {
    @Test fun `only clearly wide or tall media picks an orientation`() {
        assertEquals(LANDSCAPE, viewerFillOrientation(1920, 1080))
        assertEquals(PORTRAIT, viewerFillOrientation(1080, 1920))
        assertEquals(LANDSCAPE, viewerFillOrientation(1250, 1000))
        assertEquals(PORTRAIT, viewerFillOrientation(800, 1000))
        assertNull(viewerFillOrientation(1000, 1000))
        assertNull(viewerFillOrientation(1200, 1000))
        assertNull(viewerFillOrientation(850, 1000))
        assertNull(viewerFillOrientation(null, 1000))
        assertNull(viewerFillOrientation(1000, 0))
    }

    @Test fun `rotation is offered for mismatched videos, and for other media only while holding a rotation`() {
        assertTrue(viewerOffersRotation(LANDSCAPE, PORTRAIT, isVideo = true, holdingRotation = false))
        assertFalse(viewerOffersRotation(LANDSCAPE, LANDSCAPE, isVideo = true, holdingRotation = true))
        assertFalse(viewerOffersRotation(null, PORTRAIT, isVideo = true, holdingRotation = true))
        assertFalse(viewerOffersRotation(PORTRAIT, LANDSCAPE, isVideo = false, holdingRotation = false))
        assertTrue(viewerOffersRotation(PORTRAIT, LANDSCAPE, isVideo = false, holdingRotation = true))
    }
}
