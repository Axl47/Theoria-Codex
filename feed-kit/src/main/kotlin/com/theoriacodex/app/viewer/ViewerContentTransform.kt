package com.theoriacodex.app.viewer

import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Size

interface ViewerContentTransform {
    val zoom: Float
    val panX: Float
    val panY: Float
}

val LocalViewerContentTransform = staticCompositionLocalOf<State<ViewerContentTransform>?> { null }

data class ViewerVisibleEdge(val translationX: Float, val translationY: Float, val scaleX: Float)

fun viewerVisibleBottomEdge(frame: Size, viewport: Size, transform: ViewerContentTransform): ViewerVisibleEdge {
    val halfWidth = frame.width * transform.zoom / 2f
    val left = maxOf(transform.panX - halfWidth, -viewport.width / 2f)
    val right = minOf(transform.panX + halfWidth, viewport.width / 2f)
    val halfHeight = frame.height * transform.zoom / 2f
    val bottom = minOf(transform.panY + halfHeight, viewport.height / 2f)
    val onPage = transform.panY - halfHeight < bottom && bottom > -viewport.height / 2f
    return ViewerVisibleEdge(
        translationX = (left + right) / 2f,
        translationY = bottom - frame.height / 2f,
        scaleX = if (!onPage) 0f else if (frame.width > 0f) ((right - left) / frame.width).coerceAtLeast(0f) else 1f,
    )
}
