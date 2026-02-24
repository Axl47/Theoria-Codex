package com.theoriacodex.app.viewer

data class ViewerState(
    val streamSize: Int,
    val currentIndex: Int = 0,
    val zoom: Float = FIT_SCALE,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val chromeVisible: Boolean = true,
) {
    init {
        require(streamSize > 0) { "streamSize must be > 0" }
    }

    fun withIndex(index: Int): ViewerState {
        return copy(
            currentIndex = index.coerceIn(0, streamSize - 1),
            zoom = FIT_SCALE,
            panX = 0f,
            panY = 0f,
        )
    }

    fun next(): ViewerState {
        val target = (currentIndex + 1).coerceAtMost(streamSize - 1)
        return withIndex(target)
    }

    fun previous(): ViewerState {
        val target = (currentIndex - 1).coerceAtLeast(0)
        return withIndex(target)
    }

    fun toggleChrome(): ViewerState {
        return copy(chromeVisible = !chromeVisible)
    }

    fun hideChrome(): ViewerState {
        return copy(chromeVisible = false)
    }

    fun doubleTap(): ViewerState {
        return if (zoom <= FIT_SCALE + 0.01f) {
            copy(
                zoom = DOUBLE_TAP_SCALE,
                panX = 0f,
                panY = 0f,
            )
        } else {
            copy(
                zoom = FIT_SCALE,
                panX = 0f,
                panY = 0f,
            )
        }
    }

    fun transform(
        zoomChange: Float,
        panChangeX: Float,
        panChangeY: Float,
    ): ViewerState {
        val nextZoom = (zoom * zoomChange).coerceIn(FIT_SCALE, MAX_SCALE)
        return if (nextZoom <= FIT_SCALE + 0.01f) {
            copy(
                zoom = FIT_SCALE,
                panX = 0f,
                panY = 0f,
            )
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
    }
}
