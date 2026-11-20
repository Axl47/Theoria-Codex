package com.theoriacodex.app.viewer

import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

/** Tracks service changes while Viewer is open, without making playback depend on accessibility. */
@Composable
internal fun rememberViewerTouchExploration(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    var enabled by remember(manager) { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled = it }
        manager?.addTouchExplorationStateChangeListener(listener)
        enabled = manager?.isTouchExplorationEnabled == true
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}

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
