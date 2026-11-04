package com.theoriacodex.app.viewer

/** Pixel sizes of the page and its fitted media, which decide how far zoomed media may pan. */
internal data class ViewerTransformBounds(
    val viewportWidth: Float,
    val viewportHeight: Float,
    val contentWidth: Float,
    val contentHeight: Float,
) {
    fun clampPanX(pan: Float, zoom: Float): Float = pan.coerceIn(-overflow(contentWidth, viewportWidth, zoom), overflow(contentWidth, viewportWidth, zoom))
    fun clampPanY(pan: Float, zoom: Float): Float = pan.coerceIn(-overflow(contentHeight, viewportHeight, zoom), overflow(contentHeight, viewportHeight, zoom))

    private fun overflow(content: Float, viewport: Float, zoom: Float) = ((content * zoom - viewport) / 2f).coerceAtLeast(0f)
}

/** Fits media of [aspectRatio] inside the viewport less [padding]; unknown ratios fill it. */
internal fun viewerTransformBounds(width: Float, height: Float, padding: Float, aspectRatio: Float?): ViewerTransformBounds {
    val availableWidth = (width - padding * 2f).coerceAtLeast(0f)
    val availableHeight = (height - padding * 2f).coerceAtLeast(0f)
    val ratio = aspectRatio?.takeIf { it.isFinite() && it > 0f } ?: return ViewerTransformBounds(width, height, availableWidth, availableHeight)
    val contentWidth = minOf(availableWidth, availableHeight * ratio)
    return ViewerTransformBounds(width, height, contentWidth, contentWidth / ratio)
}

/**
 * Zoom and pan for one authoritative Viewer media identity. Pan is measured from the page centre,
 * as are the focus points, which stay under the fingers or tap while the zoom changes.
 */
internal data class ViewerTransformState(
    val zoom: Float = FIT_SCALE,
    val panX: Float = 0f,
    val panY: Float = 0f,
) {
    val isZoomed: Boolean
        get() = zoom > FIT_SCALE + FIT_TOLERANCE

    fun doubleTap(focusX: Float = 0f, focusY: Float = 0f, bounds: ViewerTransformBounds? = null): ViewerTransformState =
        if (isZoomed) ViewerTransformState() else zoomedAround(DOUBLE_TAP_SCALE, focusX, focusY, bounds)

    fun transform(
        zoomChange: Float,
        panChangeX: Float,
        panChangeY: Float,
        focusX: Float = 0f,
        focusY: Float = 0f,
        bounds: ViewerTransformBounds? = null,
    ): ViewerTransformState {
        val nextZoom = (zoom * zoomChange).coerceIn(FIT_SCALE, MAX_SCALE)
        if (nextZoom <= FIT_SCALE + FIT_TOLERANCE) return ViewerTransformState()
        return copy(panX = panX + panChangeX, panY = panY + panChangeY).zoomedAround(nextZoom, focusX, focusY, bounds)
    }

    /** Linear zoom and pan keep the focus point fixed for the whole animation. */
    fun lerpTo(target: ViewerTransformState, fraction: Float) = ViewerTransformState(
        zoom = zoom + (target.zoom - zoom) * fraction,
        panX = panX + (target.panX - panX) * fraction,
        panY = panY + (target.panY - panY) * fraction,
    )

    private fun zoomedAround(nextZoom: Float, focusX: Float, focusY: Float, bounds: ViewerTransformBounds?): ViewerTransformState {
        val ratio = nextZoom / zoom
        val nextPanX = focusX - ratio * (focusX - panX)
        val nextPanY = focusY - ratio * (focusY - panY)
        return ViewerTransformState(
            zoom = nextZoom,
            panX = bounds?.clampPanX(nextPanX, nextZoom) ?: nextPanX,
            panY = bounds?.clampPanY(nextPanY, nextZoom) ?: nextPanY,
        )
    }

    companion object {
        const val FIT_SCALE = 1f
        const val DOUBLE_TAP_SCALE = 2f
        const val MAX_SCALE = 4f
        const val FIT_TOLERANCE = 0.01f
    }
}
