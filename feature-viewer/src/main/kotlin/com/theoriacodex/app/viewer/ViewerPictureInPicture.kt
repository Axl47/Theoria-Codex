package com.theoriacodex.app.viewer

import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Rect
import android.util.Rational
import android.view.View

internal fun Context.viewerActivity(): ViewerHostActivity? = when (this) {
    is ViewerHostActivity -> this
    is ContextWrapper -> baseContext.takeIf { it !== this }?.viewerActivity()
    else -> null
}

internal fun Context.supportsViewerPictureInPicture(): Boolean =
    packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

fun viewerPictureInPictureRatio(width: Int?, height: Int?): Rational {
    val ratio = if (width != null && height != null && width > 0 && height > 0) width.toDouble() / height else 16.0 / 9.0
    return Rational((ratio.coerceIn(1.0 / 2.39, 2.39) * 10_000).toInt(), 10_000)
}

internal fun ViewerHostActivity.enterViewerPictureInPicture(width: Int?, height: Int?): Boolean {
    if (!supportsViewerPictureInPicture()) return false
    return requestViewerPictureInPicture(viewerAutoPictureInPicture.params(width, height))
}

internal fun viewerPictureInPictureParams(
    width: Int?,
    height: Int?,
    video: View?,
    autoEnter: Boolean,
): PictureInPictureParams {
    val bounds = video?.let { view -> Rect().takeIf { view.getGlobalVisibleRect(it) && !it.isEmpty } }
    return PictureInPictureParams.Builder()
        .setAspectRatio(viewerPictureInPictureRatio(width, height))
        .setSourceRectHint(bounds)
        .setAutoEnterEnabled(autoEnter)
        .build()
}

internal fun viewerPictureInPictureAction(
    activity: ViewerHostActivity?,
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
