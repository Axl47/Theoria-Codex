package com.theoriacodex.app.viewer

import android.app.PictureInPictureParams
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.util.Rational
import com.theoriacodex.app.MainActivity

internal fun Context.viewerActivity(): MainActivity? = when (this) {
    is MainActivity -> this
    is ContextWrapper -> baseContext.takeIf { it !== this }?.viewerActivity()
    else -> null
}

internal fun Context.supportsViewerPictureInPicture(): Boolean =
    packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

/** Android accepts ratios from 1:2.39 through 2.39:1, including portrait videos. */
internal fun viewerPictureInPictureRatio(width: Int?, height: Int?): Rational {
    val ratio = if (width != null && height != null && width > 0 && height > 0) width.toDouble() / height else 16.0 / 9.0
    return Rational((ratio.coerceIn(1.0 / 2.39, 2.39) * 10_000).toInt(), 10_000)
}

internal fun MainActivity.enterViewerPictureInPicture(width: Int?, height: Int?): Boolean {
    if (!supportsViewerPictureInPicture()) return false
    return requestViewerPictureInPicture(viewerPictureInPictureParams(
        width, height, autoEnter = viewerAutoPictureInPicture.keepPlayingOnPause,
    ))
}

internal fun Activity.viewerPictureInPictureParams(
    width: Int?,
    height: Int?,
    autoEnter: Boolean = false,
): PictureInPictureParams {
    val bounds = Rect().also { window.decorView.getGlobalVisibleRect(it) }
    return PictureInPictureParams.Builder()
        .setAspectRatio(viewerPictureInPictureRatio(width, height))
        .setSourceRectHint(bounds)
        .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setAutoEnterEnabled(autoEnter) }
        .build()
}

internal fun viewerPictureInPictureAction(
    activity: MainActivity?,
    post: com.theoriacodex.domain.model.Post,
    media: com.theoriacodex.domain.model.ImageRef?,
    failed: Boolean,
): (() -> Unit)? {
    if (activity == null || failed || media?.let { com.theoriacodex.app.media.isVideoMediaRef(it) } != true ||
        !activity.supportsViewerPictureInPicture()) return null
    return {
        if (!activity.enterViewerPictureInPicture(post.width, post.height)) {
            android.widget.Toast.makeText(activity, "Picture-in-picture is unavailable", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
