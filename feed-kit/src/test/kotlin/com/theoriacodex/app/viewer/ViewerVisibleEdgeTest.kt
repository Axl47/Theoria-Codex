package com.theoriacodex.app.viewer

import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerVisibleEdgeTest {
    private val frame = Size(1080f, 608f)
    private val viewport = Size(1080f, 2160f)

    private fun zoomed(zoom: Float, panX: Float = 0f, panY: Float = 0f) = object : ViewerContentTransform {
        override val zoom = zoom
        override val panX = panX
        override val panY = panY
    }

    @Test
    fun `at fit the bar keeps the content frame's bottom edge`() {
        assertEquals(ViewerVisibleEdge(0f, 0f, 1f), viewerVisibleBottomEdge(frame, viewport, zoomed(1f)))
    }

    @Test
    fun `zooming moves the bar down to the zoomed content's bottom edge`() {
        assertEquals(ViewerVisibleEdge(0f, 304f, 1f), viewerVisibleBottomEdge(frame, viewport, zoomed(2f)))
    }

    @Test
    fun `content past the screen holds the bar at the screen's bottom edge`() {
        assertEquals(ViewerVisibleEdge(0f, 776f, 1f), viewerVisibleBottomEdge(frame, viewport, zoomed(5f, panX = 400f, panY = -300f)))
    }

    @Test
    fun `a narrow frame's bar spans only the visible part of the zoomed content`() {
        val portrait = Size(540f, 2160f)
        assertEquals(ViewerVisibleEdge(135f, 0f, 1.5f), viewerVisibleBottomEdge(portrait, viewport, zoomed(1.5f, panX = 135f, panY = -540f)))
    }
}
