package com.theoriacodex.app.viewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalAccessibilityManager
import kotlinx.coroutines.delay

@Composable
internal fun ViewerChromeAutoHideEffect(
    chromeVisible: Boolean,
    interactionSerial: Int,
    interactionBlocked: Boolean,
    touchExplorationEnabled: Boolean,
    onHide: () -> Unit,
) {
    val accessibility = LocalAccessibilityManager.current
    val timeoutMillis = accessibility?.calculateRecommendedTimeoutMillis(
        originalTimeoutMillis = VIEWER_CHROME_TIMEOUT_MS,
        containsIcons = true,
        containsText = true,
        containsControls = true,
    ) ?: VIEWER_CHROME_TIMEOUT_MS
    val currentOnHide by rememberUpdatedState(onHide)
    LaunchedEffect(chromeVisible, interactionSerial, interactionBlocked, touchExplorationEnabled, timeoutMillis) {
        if (chromeVisible && !interactionBlocked && !touchExplorationEnabled) {
            delay(timeoutMillis)
            currentOnHide()
        }
    }
}

private const val VIEWER_CHROME_TIMEOUT_MS = 5_000L
