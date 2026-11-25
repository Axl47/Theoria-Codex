package com.theoriacodex.app.viewer

import com.theoriacodex.domain.model.ViewerOrientation.LANDSCAPE
import com.theoriacodex.domain.model.ViewerOrientation.PORTRAIT
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

    @Test fun `the chip is offered when media fits the other orientation, never while locked (ADR 0174)`() {
        assertTrue(viewerOffersRotation(LANDSCAPE, PORTRAIT, lock = null))
        assertFalse(viewerOffersRotation(LANDSCAPE, LANDSCAPE, lock = null))
        assertFalse(viewerOffersRotation(null, PORTRAIT, lock = null))
        assertFalse(viewerOffersRotation(LANDSCAPE, PORTRAIT, lock = PORTRAIT))
    }

    @Test fun `the sensor reads portrait or landscape only near those angles`() {
        assertEquals(PORTRAIT, viewerPhysicalOrientation(10))
        assertEquals(PORTRAIT, viewerPhysicalOrientation(350))
        assertEquals(PORTRAIT, viewerPhysicalOrientation(180))
        assertEquals(LANDSCAPE, viewerPhysicalOrientation(90))
        assertEquals(LANDSCAPE, viewerPhysicalOrientation(265))
        assertNull(viewerPhysicalOrientation(45))
        assertNull(viewerPhysicalOrientation(-1))
    }

    @Test fun `a chip rotation holds until the phone is turned to match, and only while auto-rotate is on (ADR 0174)`() {
        assertFalse(viewerHoldEnds(LANDSCAPE, PORTRAIT, autoRotate = true))
        assertFalse(viewerHoldEnds(LANDSCAPE, null, autoRotate = true))
        assertTrue(viewerHoldEnds(LANDSCAPE, LANDSCAPE, autoRotate = true))
        assertFalse(viewerHoldEnds(LANDSCAPE, LANDSCAPE, autoRotate = false))
        assertFalse(viewerHoldEnds(null, LANDSCAPE, autoRotate = true))
    }

    @Test fun `the lock pins the orientation over any hold, and with neither the sensor decides (ADR 0174)`() {
        assertEquals(PORTRAIT, viewerPinnedOrientation(lock = PORTRAIT, held = LANDSCAPE))
        assertEquals(LANDSCAPE, viewerPinnedOrientation(lock = null, held = LANDSCAPE))
        assertNull(viewerPinnedOrientation(lock = null, held = null))
    }
}
