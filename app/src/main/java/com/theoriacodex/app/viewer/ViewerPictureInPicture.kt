package com.theoriacodex.app.viewer

import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Rect
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
    val bounds = Rect().also { window.decorView.getGlobalVisibleRect(it) }
    val params = PictureInPictureParams.Builder()
        .setAspectRatio(viewerPictureInPictureRatio(width, height))
        .setSourceRectHint(bounds)
        .build()
    return requestViewerPictureInPicture(params)
}
