package com.theoriacodex.app.viewer

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import com.theoriacodex.app.media.isVideoMediaRef
import com.theoriacodex.app.viewer.state.ViewerMediaKey

internal val LocalViewerAutoRotateVideos = staticCompositionLocalOf { false }

/**
 * The orientation the Viewer holds, which the app shell applies while the Viewer is open. Null leaves
 * rotation to the phone. Owned by the Activity so the shell and the mounted video can both reach it.
 */
internal class ViewerRotationState {
    var held by mutableStateOf<ViewerOrientation?>(null)

    /** What the mounted video's decoded frames fill; covers sources that report no dimensions. */
    var measured by mutableStateOf<MeasuredVideoShape?>(null)
}

internal data class MeasuredVideoShape(val key: ViewerMediaKey, val fill: ViewerOrientation?)

/**
 * Rotates once when the Viewer opens on a clearly wide or tall video, then holds that orientation while
 * swiping. Later rotations are the user's, through the rotate button. Clears the hold when the Viewer leaves.
 */
@Composable
internal fun rememberViewerRotation(selection: ViewerSelection, rotation: ViewerRotationState?, inPictureInPicture: Boolean): ViewerRotationOffer? {
    rotation ?: return null
    val autoRotate = LocalViewerAutoRotateVideos.current
    val screen = if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) {
        ViewerOrientation.LANDSCAPE
    } else {
        ViewerOrientation.PORTRAIT
    }
    val key = ViewerMediaKey(selection.post.id, selection.mediaIndex)
    val isVideo = selection.media?.let(::isVideoMediaRef) == true
    val measured = rotation.measured?.takeIf { it.key == key }
    // A post's dimensions describe its first media item.
    val fill = when {
        measured != null -> measured.fill
        selection.mediaIndex == 0 -> viewerFillOrientation(selection.post.width, selection.post.height)
        else -> null
    }
    var openingKey by remember { mutableStateOf<ViewerMediaKey?>(key) }
    LaunchedEffect(key, fill, isVideo, autoRotate, inPictureInPicture) {
        val opening = openingKey ?: return@LaunchedEffect
        when {
            key != opening || !autoRotate || !isVideo || inPictureInPicture -> openingKey = null
            fill != null -> {
                if (fill != screen) rotation.held = fill
                openingKey = null
            }
            // Otherwise the size is still unknown: wait for the video to report it.
        }
    }
    DisposableEffect(rotation) {
        onDispose {
            rotation.held = null
            rotation.measured = null
        }
    }
    val offered = !inPictureInPicture && viewerOffersRotation(fill, screen, isVideo, holdingRotation = rotation.held != null)
    return if (offered && fill != null) ViewerRotationOffer(fill) { rotation.held = fill } else null
}

internal class ViewerRotationOffer(val target: ViewerOrientation, val rotate: () -> Unit)

/** Lower left, above the timeline, where the left thumb rests. */
@Composable
internal fun BoxScope.ViewerRotateButton(offer: ViewerRotationOffer, visible: Boolean, bottomInset: androidx.compose.ui.unit.Dp) {
    ViewerChromeVisibility(visible, Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = bottomInset + 8.dp)) {
        IconButton(
            onClick = offer.rotate,
            modifier = Modifier.background(Color.Black.copy(alpha = 0.45f), CircleShape).testTag("viewer-rotate-to-fit"),
        ) {
            Icon(Icons.Default.ScreenRotation, contentDescription = "Rotate to fit", tint = Color.White)
        }
    }
}

/** Reports what the active video's frames fill, so posts without dimensions can still rotate. */
@Composable
internal fun ReportViewerVideoShape(player: Player?, active: Boolean, key: ViewerMediaKey) {
    val rotation = LocalContext.current.viewerActivity()?.viewerRotation ?: return
    DisposableEffect(player, active, key, rotation) {
        if (player == null || !active) return@DisposableEffect onDispose {}
        fun report(size: VideoSize) {
            if (size.width <= 0 || size.height <= 0) return
            rotation.measured = MeasuredVideoShape(key, viewerFillOrientation((size.width * size.pixelWidthHeightRatio).toInt(), size.height))
        }
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) = report(videoSize)
        }
        report(player.videoSize)
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
}
