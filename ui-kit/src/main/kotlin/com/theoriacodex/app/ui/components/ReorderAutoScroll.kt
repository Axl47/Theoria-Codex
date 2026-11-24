package com.theoriacodex.app.ui.components

fun reorderAutoScrollStep(
    rowTop: Float,
    rowBottom: Float,
    viewportStart: Float,
    viewportEnd: Float,
    edgeZone: Float,
    maxStep: Float,
): Float {
    val intoTop = (viewportStart + edgeZone - rowTop) / edgeZone
    val intoBottom = (rowBottom - (viewportEnd - edgeZone)) / edgeZone
    return when {
        intoTop > 0f -> -maxStep * intoTop.coerceAtMost(1f)
        intoBottom > 0f -> maxStep * intoBottom.coerceAtMost(1f)
        else -> 0f
    }
}
