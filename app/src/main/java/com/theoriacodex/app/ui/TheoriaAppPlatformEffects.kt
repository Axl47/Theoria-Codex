package com.theoriacodex.app.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.theoriacodex.app.viewer.ViewerOrientation

/**
 * Owns only Android window policy; route and navigation state remain app-shell-owned. The Viewer follows
 * the phone's rotation unless it holds [viewerHeldOrientation], set by auto-rotate or the rotate button.
 */
@Composable
internal fun TheoriaAppPlatformEffects(
    hostActivity: Activity?,
    viewerActive: Boolean,
    viewerHeldOrientation: ViewerOrientation?,
    orientation: Int,
) {
    DisposableEffect(hostActivity, viewerActive, viewerHeldOrientation) {
        hostActivity?.requestedOrientation = when {
            !viewerActive -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            viewerHeldOrientation == ViewerOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
            viewerHeldOrientation == ViewerOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        }
        onDispose {
            hostActivity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    DisposableEffect(hostActivity, viewerActive, orientation) {
        val window = hostActivity?.window
        val insetsController = window?.let {
            WindowInsetsControllerCompat(it, it.decorView)
        }
        val landscapeFullscreen = viewerActive && orientation == Configuration.ORIENTATION_LANDSCAPE

        if (landscapeFullscreen) {
            insetsController?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController?.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            insetsController?.show(WindowInsetsCompat.Type.statusBars())
        }

        onDispose {
            insetsController?.show(WindowInsetsCompat.Type.statusBars())
        }
    }
}
