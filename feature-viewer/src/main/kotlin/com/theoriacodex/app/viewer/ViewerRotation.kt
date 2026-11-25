package com.theoriacodex.app.viewer

import android.content.res.Configuration
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ScreenLockRotation
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.outlined.ScreenLockRotation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import kotlinx.coroutines.delay

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
    return ViewerRotationOffer(
        fits = viewerOffersRotation(fill, screen, lock),
        lock = lock,
        rotate = { if (lock != null) onLockChange(lock.opposite) else rotation.held = screen.opposite },
        toggleLock = {
            if (lock == null) {
                onLockChange(screen)
            } else {
                rotation.held = null
                onLockChange(null)
            }
        },
    )
}

private fun ViewerSelection.fillOrientation(measured: MeasuredVideoShape?): ViewerOrientation? = when {
    measured != null -> measured.fill
    mediaIndex == 0 -> viewerFillOrientation(post.width, post.height)
    else -> null
}

@Composable
internal fun BoxScope.ViewerRotationChip(
    selection: ViewerSelection,
    offer: ViewerRotationOffer?,
    chromeVisible: Boolean,
    suppressed: Boolean,
    slot: Int = 0,
    bottomRowHeight: Dp = VIEWER_POST_STRIP_HEIGHT,
    inStripRow: Boolean = false,
) {
    val fits = offer?.fits == true
    var hinting by remember { mutableStateOf(false) }
    LaunchedEffect(selection.post.id, selection.mediaIndex, fits) {
        hinting = fits
        if (hinting) {
            delay(VIEWER_ROTATE_HINT_MS)
            hinting = false
        }
    }
    if (offer == null || !fits) return
    ViewerFooterButton(
        selection, chromeVisible, visible = (chromeVisible || hinting) && !suppressed, alignment = Alignment.BottomStart,
        icon = Icons.Default.ScreenRotation, contentDescription = "Rotate to fit", testTag = "viewer-rotate",
        bottomRowHeight = bottomRowHeight, inStripRow = inStripRow, slot = slot, emphasized = true, onClick = offer.rotate,
    )
}

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
