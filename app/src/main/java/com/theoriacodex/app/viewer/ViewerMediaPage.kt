package com.theoriacodex.app.viewer

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.media.isAnimatedImageMediaRef
import com.theoriacodex.app.media.isGifMediaRef
import com.theoriacodex.app.media.isPixivUgoiraMedia
import com.theoriacodex.app.media.isVideoMediaRef
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.app.viewer.state.ViewerMediaError
import com.theoriacodex.app.viewer.state.ViewerMediaKey
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey
import kotlin.math.abs

internal data class ViewerMediaLayout(
    val landscape: Boolean,
    val pictureInPicture: Boolean,
    val touchExploration: Boolean,
    val invertGalleryDirection: Boolean,
)

internal data class ViewerVideoOptions(
    val quality: com.theoriacodex.domain.model.VideoQuality,
    val height: Int?,
    val terminalMessage: String?,
    val diagnosticsEnabled: Boolean,
)

/** Owns local gestures and selects a renderer; navigation and route state stay in ViewerScreen. */
@Composable
internal fun ViewerMediaPage(
    post: Post,
    media: ImageRef,
    mediaIndex: Int,
    mediaCount: Int,
    controls: ViewerRendererControls,
    events: ViewerRendererEvents,
    transform: ViewerTransformState,
    onTransformChanged: (ViewerTransformState) -> Unit,
    layout: ViewerMediaLayout,
    videoOptions: ViewerVideoOptions,
    videoPositions: ViewerVideoPositions,
    mediaError: ViewerMediaError?,
    imageSession: ViewerImageSession,
    pixivUgoiraClient: PixivUgoiraClient?,
    onAction: (ViewerAction) -> Unit,
    onInteraction: () -> Unit,
    onToggleChrome: () -> Unit,
    onNavigate: (ViewerHorizontalSwipeDirection) -> Unit,
    onRequestMediaRecovery: ((Post, ImageRef) -> Unit)?,
) {
    val key = ViewerMediaKey(post.id, mediaIndex)
    val isVideo = isVideoMediaRef(media)
    val isGif = isGifMediaRef(media)
    val isUgoira = isPixivUgoiraMedia(post, media) && pixivUgoiraClient != null
    val isWebP = post.id.source == SourceKey.HITOMI && isAnimatedImageMediaRef(media)
    val animated = isVideo || isGif || isUgoira || isWebP
    val interactions = remember(key) { ViewerMediaInteractions() }
    val transformModifier = Modifier.graphicsLayer {
        scaleX = transform.zoom
        scaleY = transform.zoom
        translationX = transform.panX
        translationY = transform.panY
    }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().padding(if (layout.landscape || animated) 0.dp else 16.dp), contentAlignment = Alignment.Center) {
            ViewerMediaContent(
                post, media, key, controls.copy(seekJumpSerial = interactions.seekSerial, seekJumpDeltaMs = interactions.seekDelta),
                events, videoOptions, videoPositions, imageSession, pixivUgoiraClient, transformModifier, onRequestMediaRecovery,
            )
            FittedSeekJumpFeedbackOverlay(interactions.feedback, viewerAspectRatio(post), interactions::clearFeedback, Modifier.matchParentSize())
        }
        ViewerMediaGestureLayer(key, mediaCount, isVideo || isGif || isUgoira, animated, interactions, controls,
            transform, onTransformChanged, layout, onAction, onInteraction, onToggleChrome, onNavigate)
        mediaError?.let { error ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ViewerMediaErrorOverlay(error) { onAction(ViewerAction.RetryMedia) }
            }
        }
    }
}

private class ViewerMediaInteractions {
    var seekSerial by mutableIntStateOf(0)
    var seekDelta by mutableLongStateOf(0L)
    var feedback by mutableStateOf<SeekJumpFeedback?>(null)

    fun seek(delta: Long) {
        seekDelta = delta
        seekSerial += 1
        feedback = nextSeekJumpFeedback(feedback, delta, SystemClock.elapsedRealtime(), seekSerial)
    }

    fun clearFeedback(serial: Int) { if (feedback?.serial == serial) feedback = null }
}

private fun viewerAspectRatio(post: Post): Float? {
    val width = post.width?.takeIf { it > 0 } ?: return null
    val height = post.height?.takeIf { it > 0 } ?: return null
    return width.toFloat() / height.toFloat()
}

@Composable
private fun ViewerMediaGestureLayer(
    key: ViewerMediaKey, mediaCount: Int, seekable: Boolean, animated: Boolean,
    interactions: ViewerMediaInteractions, controls: ViewerRendererControls,
    transform: ViewerTransformState, onTransformChanged: (ViewerTransformState) -> Unit,
    layout: ViewerMediaLayout, onAction: (ViewerAction) -> Unit, onInteraction: () -> Unit,
    onToggleChrome: () -> Unit, onNavigate: (ViewerHorizontalSwipeDirection) -> Unit,
) {
    val transformState = rememberTransformableState { _, zoom, pan, _ ->
        onTransformChanged(transform.transform(zoom, pan.x, pan.y))
        onInteraction()
    }
    val gestures = viewerMediaGestureModifier(
        mediaKey = key, active = controls.isActive && !layout.pictureInPicture, seekable = seekable,
        zoomed = transform.isZoomed, chromeVisible = controls.showControls, touchExplorationEnabled = layout.touchExploration,
        actions = ViewerMediaGestureActions(
            seek = { interactions.seek(it); onInteraction() },
            zoom = { onTransformChanged(transform.doubleTap()); onInteraction() },
            toggleControls = onToggleChrome,
            showInfo = { onAction(ViewerAction.ShowMetadata); onInteraction() },
        ),
    )
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()
            .then(viewerHorizontalSwipeModifier(key, transform.isZoomed, mediaCount, layout.invertGalleryDirection, onNavigate))
            .transformable(transformState, canPan = { transform.isZoomed }).then(gestures))
        if (controls.showControls && animated) Spacer(Modifier.fillMaxWidth().height(96.dp))
    }
}

@Composable
private fun viewerHorizontalSwipeModifier(
    key: ViewerMediaKey,
    zoomed: Boolean,
    mediaCount: Int,
    invertMultiImageScrollDirection: Boolean,
    onNavigate: (ViewerHorizontalSwipeDirection) -> Unit,
): Modifier {
    val currentOnNavigate by rememberUpdatedState(onNavigate)
    return Modifier.pointerInput(key, zoomed, mediaCount, invertMultiImageScrollDirection) {
        if (zoomed) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val threshold = maxOf(VIEWER_HORIZONTAL_SWIPE_MIN_DISTANCE_PX, size.width * VIEWER_HORIZONTAL_SWIPE_WIDTH_RATIO)
            val swipe = readViewerSwipe(down.id, threshold)
            if (swipe.completed(threshold)) {
                val direction = if (swipe.x < 0f) ViewerHorizontalSwipeDirection.Next else ViewerHorizontalSwipeDirection.Previous
                currentOnNavigate(viewerSwipeDirectionForSetting(direction, mediaCount, invertMultiImageScrollDirection))
            }
        }
    }
}

private class ViewerSwipeMotion {
    var x = 0f
    var y = 0f
    var consuming = false

    fun add(delta: Offset, threshold: Float) {
        x += delta.x
        y += delta.y
        if (abs(x) > threshold * VIEWER_HORIZONTAL_SWIPE_SLOP_FRACTION && abs(x) > abs(y) * VIEWER_HORIZONTAL_SWIPE_AXIS_RATIO) {
            consuming = true
        }
    }

    fun completed(threshold: Float): Boolean = consuming && abs(x) >= threshold && abs(x) > abs(y) * VIEWER_HORIZONTAL_SWIPE_AXIS_RATIO
}

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.readViewerSwipe(
    id: androidx.compose.ui.input.pointer.PointerId,
    threshold: Float,
): ViewerSwipeMotion {
    val swipe = ViewerSwipeMotion()
    while (true) {
        val event = awaitPointerEvent(pass = PointerEventPass.Initial)
        if (event.changes.size > 1) break
        val change = event.changes.firstOrNull { it.id == id } ?: break
        swipe.add(change.position - change.previousPosition, threshold)
        if (swipe.consuming) change.consume()
        if (!change.pressed) break
    }
    return swipe
}

private const val VIEWER_HORIZONTAL_SWIPE_MIN_DISTANCE_PX = 48f
private const val VIEWER_HORIZONTAL_SWIPE_WIDTH_RATIO = 0.12f
private const val VIEWER_HORIZONTAL_SWIPE_SLOP_FRACTION = 0.35f
private const val VIEWER_HORIZONTAL_SWIPE_AXIS_RATIO = 1.25f
