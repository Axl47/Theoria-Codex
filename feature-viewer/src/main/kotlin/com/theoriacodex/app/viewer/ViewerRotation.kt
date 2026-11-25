package com.theoriacodex.app.viewer

import android.content.res.Configuration
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import com.theoriacodex.app.media.isVideoMediaRef
import com.theoriacodex.app.viewer.state.ViewerMediaKey
import com.theoriacodex.domain.model.Post
import kotlinx.coroutines.delay

val LocalViewerAutoRotateVideos = staticCompositionLocalOf { false }

class ViewerRotationState {
    var held by mutableStateOf<ViewerOrientation?>(null)

    var measured by mutableStateOf<MeasuredVideoShape?>(null)

    private var openingClaimed = false

    internal var openingKey: ViewerMediaKey? = null

    internal fun openingFor(key: ViewerMediaKey): ViewerMediaKey? {
        if (!openingClaimed) {
            openingClaimed = true
            openingKey = key
        }
        return openingKey
    }

    fun release() {
        held = null
        measured = null
        openingClaimed = false
        openingKey = null
    }
}

data class MeasuredVideoShape(val key: ViewerMediaKey, val fill: ViewerOrientation?)

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
    val isVideo = selection.isVideoForRotation()
    val fill = selection.fillOrientation(rotation.measured?.takeIf { it.key == key })
    LaunchedEffect(key, fill, isVideo, autoRotate, inPictureInPicture) {
        val opening = rotation.openingFor(key) ?: return@LaunchedEffect
        when {
            key != opening || !autoRotate || inPictureInPicture -> rotation.openingKey = null
            isVideo && fill != null -> {
                if (fill != screen) rotation.held = fill
                rotation.openingKey = null
            }
        }
    }
    if (inPictureInPicture) return null
    val fits = viewerOffersRotation(fill, screen, isVideo, holdingRotation = rotation.held != null)
    val target = screen.opposite
    return ViewerRotationOffer(fits) {
        rotation.openingKey = null
        rotation.held = target
    }
}

private fun ViewerSelection.isVideoForRotation(): Boolean =
    media?.let(::isVideoMediaRef) == true || (mediaIndex == 0 && post.awaitsMotion)

private fun ViewerSelection.fillOrientation(measured: MeasuredVideoShape?): ViewerOrientation? = when {
    measured != null -> measured.fill
    mediaIndex == 0 -> viewerFillOrientation(post.width, post.height)
    else -> null
}

internal class ViewerRotationOffer(val fits: Boolean, val rotate: () -> Unit)

@Composable
internal fun BoxScope.ViewerRotationControl(
    selection: ViewerSelection,
    rotation: ViewerRotationState?,
    inPictureInPicture: Boolean,
    chromeVisible: Boolean,
    suppressed: Boolean,
    bottomRowHeight: Dp = VIEWER_POST_STRIP_HEIGHT,
    existingOffer: ViewerRotationOffer? = null,
    inStripRow: Boolean = false,
) {
    val offer = existingOffer ?: rememberViewerRotation(selection, rotation, inPictureInPicture)
    var hinting by remember { mutableStateOf(false) }
    LaunchedEffect(selection.post.id, selection.mediaIndex, offer?.fits) {
        hinting = offer?.fits == true
        if (hinting) {
            delay(VIEWER_ROTATE_HINT_MS)
            hinting = false
        }
    }
    offer ?: return
    ViewerFooterButton(
        selection, chromeVisible, visible = (chromeVisible || hinting) && !suppressed, alignment = Alignment.BottomStart,
        icon = Icons.Default.ScreenRotation, contentDescription = if (offer.fits) "Rotate to fit" else "Rotate", testTag = "viewer-rotate",
        bottomRowHeight = bottomRowHeight, inStripRow = inStripRow, onClick = offer.rotate,
    )
}

internal const val VIEWER_ROTATE_HINT_MS = 3_000L

@Composable
internal fun ReportViewerVideoShape(player: Player?, active: Boolean, key: ViewerMediaKey) {
    val rotation = LocalContext.current.viewerActivity()?.viewerRotation ?: return
    DisposableEffect(player, active, key, rotation) {
        if (player == null || !active) return@DisposableEffect onDispose {}
        fun report(size: VideoSize) {
            if (size.width <= 0 || size.height <= 0) return
            rotation.measured = MeasuredVideoShape(key, viewerFillOrientation(size.displayWidth, size.height))
        }
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) = report(videoSize)
        }
        report(player.videoSize)
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
}
