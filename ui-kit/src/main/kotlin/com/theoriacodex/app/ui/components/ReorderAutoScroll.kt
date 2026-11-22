package com.theoriacodex.app.ui.components

/**
 * How far a list scrolls this frame while a dragged row sits in an edge zone: the speed grows from zero at the zone's
 * inner edge to [maxStep] at the viewport's edge.
 */
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
