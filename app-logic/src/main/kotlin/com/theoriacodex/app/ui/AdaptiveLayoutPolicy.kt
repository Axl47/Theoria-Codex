package com.theoriacodex.app.ui

fun adaptiveFeedColumns(widthDp: Float): Int = if (widthDp < 600f) 2 else (widthDp / 220f).toInt().coerceIn(2, 4)
fun useNavigationRail(widthDp: Float): Boolean = widthDp >= 600f
fun useViewerInfoSidePanel(widthDp: Float): Boolean = widthDp >= 1000f
