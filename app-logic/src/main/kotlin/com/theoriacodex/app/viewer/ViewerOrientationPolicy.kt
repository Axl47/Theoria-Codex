package com.theoriacodex.app.viewer

enum class ViewerOrientation {
    PORTRAIT, LANDSCAPE;

    val opposite: ViewerOrientation get() = if (this == PORTRAIT) LANDSCAPE else PORTRAIT
}

const val VIEWER_ROTATION_MIN_ASPECT = 1.25f

fun viewerFillOrientation(width: Int?, height: Int?): ViewerOrientation? {
    if (width == null || height == null || width <= 0 || height <= 0) return null
    val ratio = width.toFloat() / height
    return when {
        ratio >= VIEWER_ROTATION_MIN_ASPECT -> ViewerOrientation.LANDSCAPE
        ratio <= 1f / VIEWER_ROTATION_MIN_ASPECT -> ViewerOrientation.PORTRAIT
        else -> null
    }
}

fun viewerOffersRotation(
    fill: ViewerOrientation?,
    screen: ViewerOrientation,
    isVideo: Boolean,
    holdingRotation: Boolean,
): Boolean = fill != null && fill != screen && (isVideo || holdingRotation)
