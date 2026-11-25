package com.theoriacodex.app.ui

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.provider.Settings
import android.view.OrientationEventListener
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.theoriacodex.app.viewer.ViewerRotationState
import com.theoriacodex.app.viewer.viewerHoldEnds
import com.theoriacodex.app.viewer.viewerPhysicalOrientation
import com.theoriacodex.app.viewer.viewerPinnedOrientation
import com.theoriacodex.domain.model.ViewerOrientation

@Composable
internal fun TheoriaAppPlatformEffects(
    hostActivity: Activity?,
    viewerActive: Boolean,
    viewerRotation: ViewerRotationState?,
    orientationLock: ViewerOrientation?,
    orientation: Int,
) {
    DisposableEffect(viewerRotation, viewerActive) {
        onDispose { if (viewerActive) viewerRotation?.release() }
    }

    val pinned = viewerPinnedOrientation(orientationLock, viewerRotation?.held)
    val screen = if (orientation == Configuration.ORIENTATION_LANDSCAPE) ViewerOrientation.LANDSCAPE else ViewerOrientation.PORTRAIT
    DisposableEffect(hostActivity, viewerActive, pinned, screen) {
        hostActivity?.requestedOrientation = if (viewerActive) viewerScreenOrientation(pinned, screen) else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onDispose {
            hostActivity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    ReleaseViewerHoldWhenTurned(hostActivity, viewerActive, viewerRotation, orientationLock)

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

internal fun viewerScreenOrientation(pinned: ViewerOrientation?, screen: ViewerOrientation): Int = when (pinned) {
    null -> ActivityInfo.SCREEN_ORIENTATION_USER
    screen -> ActivityInfo.SCREEN_ORIENTATION_LOCKED
    ViewerOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
    ViewerOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
}

@Composable
private fun ReleaseViewerHoldWhenTurned(activity: Activity?, viewerActive: Boolean, rotation: ViewerRotationState?, lock: ViewerOrientation?) {
    val held = rotation?.held
    DisposableEffect(activity, viewerActive, rotation, held, lock) {
        if (activity == null || rotation == null || held == null || lock != null || !viewerActive) return@DisposableEffect onDispose {}
        val listener = object : OrientationEventListener(activity) {
            override fun onOrientationChanged(degrees: Int) {
                val physical = viewerPhysicalOrientation(degrees)
                if (physical == held && viewerHoldEnds(held, physical, systemAutoRotate(activity))) rotation.held = null
            }
        }
        listener.enable()
        onDispose { listener.disable() }
    }
}

private fun systemAutoRotate(context: Context): Boolean =
    Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
