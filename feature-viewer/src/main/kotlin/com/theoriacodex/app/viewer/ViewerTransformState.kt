package com.theoriacodex.app.viewer

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

internal fun viewerTransformBounds(width: Float, height: Float, aspectRatio: Float?): ViewerTransformBounds {
    val ratio = aspectRatio?.takeIf { it.isFinite() && it > 0f } ?: return ViewerTransformBounds(width, height, width, height)
    val contentWidth = minOf(width, height * ratio)
    return ViewerTransformBounds(width, height, contentWidth, contentWidth / ratio)
}

internal data class ViewerTransformState(
    override val zoom: Float = FIT_SCALE,
    override val panX: Float = 0f,
    override val panY: Float = 0f,
) : ViewerContentTransform {
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
