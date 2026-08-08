package com.theoriacodex.app.ui

internal data class BottomNavigationSizing(
    val totalHeightDp: Float,
    val iconSizeDp: Int,
)

/**
 * Keeps the navigation controls at the established gesture-navigation size while allowing
 * Android's larger three-button inset to occupy additional space below those controls.
 */
internal fun calculateBottomNavigationSizing(
    windowHeightDp: Float,
    bottomSystemInsetDp: Float,
): BottomNavigationSizing {
    val baseHeightDp = (windowHeightDp * BOTTOM_BAR_HEIGHT_RATIO)
        .toInt()
        .coerceIn(MIN_BOTTOM_BAR_HEIGHT_DP, MAX_BOTTOM_BAR_HEIGHT_DP)
    val iconSizeDp = (baseHeightDp * BOTTOM_BAR_ICON_RATIO)
        .toInt()
        .coerceIn(MIN_BOTTOM_BAR_ICON_DP, MAX_BOTTOM_BAR_ICON_DP)
    val additionalSystemInsetDp =
        (bottomSystemInsetDp - INCLUDED_GESTURE_INSET_DP).coerceAtLeast(0f)

    return BottomNavigationSizing(
        totalHeightDp = baseHeightDp + additionalSystemInsetDp,
        iconSizeDp = iconSizeDp,
    )
}

private const val BOTTOM_BAR_HEIGHT_RATIO = 0.085f
private const val BOTTOM_BAR_ICON_RATIO = 0.38f
private const val MIN_BOTTOM_BAR_HEIGHT_DP = 68
private const val MAX_BOTTOM_BAR_HEIGHT_DP = 88
private const val MIN_BOTTOM_BAR_ICON_DP = 24
private const val MAX_BOTTOM_BAR_ICON_DP = 30

// Android's standard gesture-navigation area is already part of the established bar height.
private const val INCLUDED_GESTURE_INSET_DP = 24f
