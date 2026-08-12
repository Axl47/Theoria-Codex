package com.theoriacodex.app.viewer

/** Zoom and pan for one authoritative Viewer media identity. */
internal data class ViewerTransformState(
    val zoom: Float = FIT_SCALE,
    val panX: Float = 0f,
    val panY: Float = 0f,
) {
    val isZoomed: Boolean
        get() = zoom > FIT_SCALE + FIT_TOLERANCE

    fun doubleTap(): ViewerTransformState {
        return if (zoom <= FIT_SCALE + FIT_TOLERANCE) {
            copy(
                zoom = DOUBLE_TAP_SCALE,
                panX = 0f,
                panY = 0f,
            )
        } else {
            ViewerTransformState()
        }
    }

    fun transform(
        zoomChange: Float,
        panChangeX: Float,
        panChangeY: Float,
    ): ViewerTransformState {
        val nextZoom = (zoom * zoomChange).coerceIn(FIT_SCALE, MAX_SCALE)
        return if (nextZoom <= FIT_SCALE + FIT_TOLERANCE) {
            ViewerTransformState()
        } else {
            copy(
                zoom = nextZoom,
                panX = panX + panChangeX,
                panY = panY + panChangeY,
            )
        }
    }

    companion object {
        const val FIT_SCALE = 1f
        const val DOUBLE_TAP_SCALE = 2f
        const val MAX_SCALE = 4f
        const val FIT_TOLERANCE = 0.01f
    }
}
