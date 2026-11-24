package com.theoriacodex.app.viewer

import android.app.PictureInPictureParams
import androidx.activity.ComponentActivity

abstract class ViewerHostActivity : ComponentActivity() {
    abstract val viewerPictureInPictureActive: Boolean
    abstract val viewerAutoPictureInPicture: ViewerAutoPictureInPicture
    abstract val viewerRotation: ViewerRotationState
    abstract val keepViewerPlayingOnPause: Boolean

    abstract fun requestViewerPictureInPicture(params: PictureInPictureParams): Boolean
}
