package com.theoriacodex.app.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerTextOverlayGeometryTest {
    @Test
    fun `padded Viewer transform keeps fitted image centered in gesture coordinates`() {
        val transform = requireNotNull(
            viewerOcrTransform(
                viewportWidth = 300f,
                viewportHeight = 300f,
                imageWidth = 200,
                imageHeight = 100,
                viewerTransform = ViewerTransformState(),
                contentPaddingPx = 16f,
            ),
        )

        assertEquals(16f, transform.fittedImageRect.left, 0.001f)
        assertEquals(284f, transform.fittedImageRect.right, 0.001f)
        assertEquals(83f, transform.fittedImageRect.top, 0.001f)
        assertEquals(217f, transform.fittedImageRect.bottom, 0.001f)
        assertEquals(ViewerOcrPoint(150f, 150f), transform.layerBounds.center)
    }
}
