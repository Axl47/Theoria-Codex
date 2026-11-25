package com.theoriacodex.app.viewer

import android.content.res.Configuration
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ScreenLockRotation
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.outlined.ScreenLockRotation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import com.theoriacodex.app.viewer.state.ViewerMediaKey
import com.theoriacodex.domain.model.ViewerOrientation

class ViewerRotationState {
    var held by mutableStateOf<ViewerOrientation?>(null)

    var measured by mutableStateOf<MeasuredVideoShape?>(null)

    fun release() {
        held = null
        measured = null
    }
}

data class MeasuredVideoShape(val key: ViewerMediaKey, val fill: ViewerOrientation?)

internal class ViewerRotationOffer(
    val fits: Boolean,
    val target: ViewerOrientation,
    val lock: ViewerOrientation?,
    val rotate: () -> Unit,
    val toggleLock: () -> Unit,
)

@Composable
internal fun rememberViewerRotation(
    selection: ViewerSelection,
    rotation: ViewerRotationState?,
    inPictureInPicture: Boolean,
    lock: ViewerOrientation?,
    onLockChange: (ViewerOrientation?) -> Unit,
): ViewerRotationOffer? {
    rotation ?: return null
    if (inPictureInPicture) return null
    val screen = if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) {
        ViewerOrientation.LANDSCAPE
    } else {
        ViewerOrientation.PORTRAIT
    }
    val key = ViewerMediaKey(selection.post.id, selection.mediaIndex)
    val fill = selection.fillOrientation(rotation.measured?.takeIf { it.key == key })
    val target = screen.opposite
    return ViewerRotationOffer(
        fits = viewerFitsOtherWay(fill, screen),
        target = target,
        lock = lock,
        rotate = { rotation.held = target },
        toggleLock = { onLockChange(if (lock == null) screen else null) },
    )
}

private fun ViewerSelection.fillOrientation(measured: MeasuredVideoShape?): ViewerOrientation? = when {
    measured != null -> measured.fill
    mediaIndex == 0 -> viewerFillOrientation(post.width, post.height)
    else -> null
}

@Composable
internal fun BoxScope.ViewerRotateButton(
    selection: ViewerSelection,
    offer: ViewerRotationOffer?,
    chromeVisible: Boolean,
    suppressed: Boolean,
    slot: Int = 0,
    bottomRowHeight: Dp = VIEWER_POST_STRIP_HEIGHT,
    inStripRow: Boolean = false,
) {
    if (offer == null || offer.lock != null) return
    ViewerFooterButton(
        selection, chromeVisible, visible = chromeVisible && !suppressed, alignment = Alignment.BottomStart,
        icon = Icons.Default.ScreenRotation, contentDescription = offer.rotateDescription, testTag = VIEWER_ROTATE_TAG,
        bottomRowHeight = bottomRowHeight, inStripRow = inStripRow, slot = slot, emphasized = offer.fits, onClick = offer.rotate,
    )
}

internal val ViewerRotationOffer.rotateDescription: String
    get() = if (target == ViewerOrientation.LANDSCAPE) "Rotate to landscape" else "Rotate to portrait"

internal const val VIEWER_ROTATE_TAG = "viewer-rotate"

@Composable
internal fun BoxScope.ViewerRotationLockButton(
    selection: ViewerSelection,
    offer: ViewerRotationOffer?,
    chromeVisible: Boolean,
    suppressed: Boolean,
    alignment: Alignment,
    slot: Int = 0,
    inStripRow: Boolean = false,
) {
    offer ?: return
    ViewerFooterButton(
        selection, chromeVisible, visible = chromeVisible && !suppressed, alignment = alignment,
        icon = offer.lockIcon, contentDescription = offer.lockDescription, testTag = VIEWER_ROTATION_LOCK_TAG,
        inStripRow = inStripRow, slot = slot, emphasized = offer.lock != null, onClick = offer.toggleLock,
    )
}

internal val ViewerRotationOffer.lockIcon: ImageVector
    get() = if (lock != null) Icons.Filled.ScreenLockRotation else Icons.Outlined.ScreenLockRotation

internal val ViewerRotationOffer.lockDescription: String
    get() = if (lock != null) "Unlock rotation" else "Lock rotation"

internal const val VIEWER_ROTATION_LOCK_TAG = "viewer-rotation-lock"

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
