package com.theoriacodex.app.viewer

import com.theoriacodex.domain.model.ViewerOrientation
import kotlin.math.abs

const val VIEWER_ROTATION_MIN_ASPECT = 1.25f
const val VIEWER_SENSOR_TOLERANCE_DEGREES = 30

fun viewerFillOrientation(width: Int?, height: Int?): ViewerOrientation? {
    if (width == null || height == null || width <= 0 || height <= 0) return null
    val ratio = width.toFloat() / height
    return when {
        ratio >= VIEWER_ROTATION_MIN_ASPECT -> ViewerOrientation.LANDSCAPE
        ratio <= 1f / VIEWER_ROTATION_MIN_ASPECT -> ViewerOrientation.PORTRAIT
        else -> null
    }
}

fun viewerFitsOtherWay(fill: ViewerOrientation?, screen: ViewerOrientation): Boolean = fill != null && fill != screen

fun viewerPhysicalOrientation(degrees: Int): ViewerOrientation? {
    if (degrees < 0) return null
    val half = degrees % 180
    return when {
        half <= VIEWER_SENSOR_TOLERANCE_DEGREES || half >= 180 - VIEWER_SENSOR_TOLERANCE_DEGREES -> ViewerOrientation.PORTRAIT
        abs(half - 90) <= VIEWER_SENSOR_TOLERANCE_DEGREES -> ViewerOrientation.LANDSCAPE
        else -> null
    }
}

fun viewerHoldEnds(held: ViewerOrientation?, physical: ViewerOrientation?, autoRotate: Boolean): Boolean =
    autoRotate && held != null && physical == held

fun viewerPinnedOrientation(lock: ViewerOrientation?, held: ViewerOrientation?): ViewerOrientation? = lock ?: held
