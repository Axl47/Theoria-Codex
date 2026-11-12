package com.theoriacodex.app.viewer

/** A screen orientation the Viewer can hold to fit its media. */
enum class ViewerOrientation { PORTRAIT, LANDSCAPE }

/** Media must be at least this much wider than tall (or taller than wide) before rotating gains real space. */
const val VIEWER_ROTATION_MIN_ASPECT = 1.25f

/** The orientation that [width]×[height] media fills, or null when its size is unknown or it is near square. */
fun viewerFillOrientation(width: Int?, height: Int?): ViewerOrientation? {
    if (width == null || height == null || width <= 0 || height <= 0) return null
    val ratio = width.toFloat() / height
    return when {
        ratio >= VIEWER_ROTATION_MIN_ASPECT -> ViewerOrientation.LANDSCAPE
        ratio <= 1f / VIEWER_ROTATION_MIN_ASPECT -> ViewerOrientation.PORTRAIT
        else -> null
    }
}

/**
 * Whether the Viewer offers to rotate to [fill]. Videos always qualify; other media only once the Viewer
 * holds a rotation, so a rotation chosen for a video can always be undone from the posts after it.
 */
fun viewerOffersRotation(
    fill: ViewerOrientation?,
    screen: ViewerOrientation,
    isVideo: Boolean,
    holdingRotation: Boolean,
): Boolean = fill != null && fill != screen && (isVideo || holdingRotation)
