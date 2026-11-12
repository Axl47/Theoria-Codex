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
import com.theoriacodex.domain.model.Post
import kotlinx.coroutines.delay

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
 * swiping. Later rotations are the user's, through the rotate button, which always turns to the other orientation
 * (outside picture-in-picture). Clears the hold when the Viewer leaves.
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
    val isVideo = selection.isVideoForRotation()
    val fill = selection.fillOrientation(rotation.measured?.takeIf { it.key == key })
    var openingKey by remember { mutableStateOf<ViewerMediaKey?>(key) }
    LaunchedEffect(key, fill, isVideo, autoRotate, inPictureInPicture) {
        val opening = openingKey ?: return@LaunchedEffect
        when {
            key != opening || !autoRotate || inPictureInPicture -> openingKey = null
            isVideo && fill != null -> {
                if (fill != screen) rotation.held = fill
                openingKey = null
            }
            // Otherwise wait: a post without dimensions learns its size once the video decodes.
        }
    }
    DisposableEffect(rotation) {
        onDispose {
            rotation.held = null
            rotation.measured = null
        }
    }
    if (inPictureInPicture) return null
    val fits = viewerOffersRotation(fill, screen, isVideo, holdingRotation = rotation.held != null)
    val target = screen.opposite
    return ViewerRotationOffer(fits) {
        // The user's choice wins over an automatic rotation still waiting on the video's size.
        openingKey = null
        rotation.held = target
    }
}

/** Videos qualify for rotation, including ones whose stream has not resolved yet. */
private fun ViewerSelection.isVideoForRotation(): Boolean =
    media?.let(::isVideoMediaRef) == true || (mediaIndex == 0 && post.awaitsVideoStream())

/** What the current media fills: its decoded frames when known, else the post's dimensions, which describe its first item. */
private fun ViewerSelection.fillOrientation(measured: MeasuredVideoShape?): ViewerOrientation? = when {
    measured != null -> measured.fill
    mediaIndex == 0 -> viewerFillOrientation(post.width, post.height)
    else -> null
}

/**
 * A video whose stream resolves only after it opens: its listing gave just a preview and a running time.
 * Lets the Viewer rotate at once instead of waiting for the stream. GIFs and ugoira carry their media up front.
 */
private fun Post.awaitsVideoStream(): Boolean = full == null && media.isEmpty() && (durationMs ?: 0L) > 0L

/** The rotate button's action; [fits] when the other orientation suits the current media better. */
internal class ViewerRotationOffer(val fits: Boolean, val rotate: () -> Unit)

/**
 * The Viewer's rotation behavior and its button, placed above whichever bar the current media shows. The button
 * follows the controls, and also shows for [VIEWER_ROTATE_HINT_MS] whenever rotating starts to fit a post,
 * since swiping to a post leaves the controls hidden. [suppressed] hides it while another surface owns the bottom.
 */
@Composable
internal fun BoxScope.ViewerRotationControl(
    selection: ViewerSelection,
    rotation: ViewerRotationState?,
    inPictureInPicture: Boolean,
    chromeVisible: Boolean,
    suppressed: Boolean,
) {
    val offer = rememberViewerRotation(selection, rotation, inPictureInPicture)
    var hinting by remember { mutableStateOf(false) }
    LaunchedEffect(selection.post.id, selection.mediaIndex, offer?.fits) {
        hinting = offer?.fits == true
        if (hinting) {
            delay(VIEWER_ROTATE_HINT_MS)
            hinting = false
        }
    }
    offer ?: return
    val bottomBar = when {
        selection.isAnimated -> viewerTimelineFooterHeight()
        selection.overviewItems.size > 1 -> VIEWER_TIMELINE_TRACK_HEIGHT
        else -> 0.dp
    }
    ViewerRotateButton(offer, (chromeVisible || hinting) && !suppressed, bottomInset = LocalViewerPostStripInset.current + bottomBar)
}

internal const val VIEWER_ROTATE_HINT_MS = 3_000L

/** Lower left, above the timeline, where the left thumb rests. */
@Composable
internal fun BoxScope.ViewerRotateButton(offer: ViewerRotationOffer, visible: Boolean, bottomInset: androidx.compose.ui.unit.Dp) {
    ViewerChromeVisibility(visible, Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = bottomInset + 8.dp)) {
        IconButton(
            onClick = offer.rotate,
            modifier = Modifier.background(Color.Black.copy(alpha = 0.45f), CircleShape).testTag("viewer-rotate"),
        ) {
            Icon(Icons.Default.ScreenRotation, contentDescription = if (offer.fits) "Rotate to fit" else "Rotate", tint = Color.White)
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
