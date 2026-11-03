package com.theoriacodex.app.viewer

import android.os.SystemClock
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.theoriacodex.app.media.isAnimatedImageMediaRef
import com.theoriacodex.app.media.isGifMediaRef
import com.theoriacodex.app.media.isPixivUgoiraMedia
import com.theoriacodex.app.media.isVideoMediaRef
import com.theoriacodex.app.ui.motion.TheoriaMotion
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.app.viewer.state.ViewerMediaError
import com.theoriacodex.app.viewer.state.ViewerMediaKey
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey

internal data class ViewerMediaLayout(
    val landscape: Boolean,
    val pictureInPicture: Boolean,
    val touchExploration: Boolean,
)

internal data class ViewerVideoOptions(
    val quality: com.theoriacodex.domain.model.VideoQuality,
    val height: Int?,
    val terminalMessage: String?,
    val diagnosticsEnabled: Boolean,
)

/** Owns taps and pinches and selects a renderer; swipes and route state stay in ViewerScreen. */
@Composable
internal fun ViewerMediaPage(
    post: Post,
    media: ImageRef,
    mediaIndex: Int,
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
    onRequestMediaRecovery: ((Post, ImageRef) -> Unit)?,
    levelControls: ViewerLevelControls? = null,
    returnProgress: Float = 0f,
) {
    val key = ViewerMediaKey(post.id, mediaIndex)
    val isVideo = isVideoMediaRef(media)
    val isGif = isGifMediaRef(media)
    val isUgoira = isPixivUgoiraMedia(post, media) && pixivUgoiraClient != null
    val animated = viewerMediaIsAnimated(post, media, pixivUgoiraClient != null)
    val interactions = remember(key) { ViewerMediaInteractions() }
    val transformModifier = Modifier.graphicsLayer {
        scaleX = transform.zoom
        scaleY = transform.zoom
        translationX = transform.panX
        translationY = transform.panY
    }
    val mediaPadding = if (layout.landscape || animated) 0.dp else 16.dp
    var pageSize by remember { mutableStateOf(IntSize.Zero) }
    val transformBounds = with(LocalDensity.current) {
        viewerTransformBounds(pageSize.width.toFloat(), pageSize.height.toFloat(), mediaPadding.toPx(), viewerAspectRatio(post))
    }
    Box(Modifier.fillMaxSize().onSizeChanged { pageSize = it }) {
        Box(
            Modifier.fillMaxSize().padding(mediaPadding)
                .viewerReturnMedia(returnProgress, viewerAspectRatio(post)),
            contentAlignment = Alignment.Center,
        ) {
            ViewerMediaContent(
                post, media, key, controls.copy(seekJumpSerial = interactions.seekSerial, seekJumpDeltaMs = interactions.seekDelta),
                events, videoOptions, videoPositions, imageSession, pixivUgoiraClient, transformModifier, onRequestMediaRecovery,
            )
            FittedSeekJumpFeedbackOverlay(interactions.feedback, viewerAspectRatio(post), interactions::clearFeedback, Modifier.matchParentSize())
        }
        ViewerMediaGestureLayer(key, isVideo || isGif || isUgoira, animated, interactions, controls,
            transform, transformBounds, onTransformChanged, layout, onAction, onInteraction, onToggleChrome)
        if (controls.isActive && isVideo && !layout.pictureInPicture && levelControls != null) {
            FittedViewerMediaOverlay(viewerAspectRatio(post), Modifier.matchParentSize()) {
                ViewerLevelFeedbackOverlay(levelControls, Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
            }
        }
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
    var zoomAnimation: Job? = null

    fun seek(delta: Long) {
        seekDelta = delta
        seekSerial += 1
        feedback = nextSeekJumpFeedback(feedback, delta, SystemClock.elapsedRealtime(), seekSerial)
    }

    fun clearFeedback(serial: Int) { if (feedback?.serial == serial) feedback = null }
}

/** Animated media carries a timeline, which reserves the bottom of the page for its own drags. */
internal fun viewerMediaIsAnimated(post: Post, media: ImageRef, hasUgoiraClient: Boolean): Boolean =
    isVideoMediaRef(media) || isGifMediaRef(media) || (isPixivUgoiraMedia(post, media) && hasUgoiraClient) ||
        (post.id.source == SourceKey.HITOMI && isAnimatedImageMediaRef(media))

private fun viewerAspectRatio(post: Post): Float? {
    val width = post.width?.takeIf { it > 0 } ?: return null
    val height = post.height?.takeIf { it > 0 } ?: return null
    return width.toFloat() / height.toFloat()
}

@Composable
private fun ViewerMediaGestureLayer(
    key: ViewerMediaKey, seekable: Boolean, animated: Boolean,
    interactions: ViewerMediaInteractions, controls: ViewerRendererControls,
    transform: ViewerTransformState, bounds: ViewerTransformBounds, onTransformChanged: (ViewerTransformState) -> Unit,
    layout: ViewerMediaLayout, onAction: (ViewerAction) -> Unit, onInteraction: () -> Unit,
    onToggleChrome: () -> Unit,
) {
    val currentTransform by rememberUpdatedState(transform)
    val currentOnTransformChanged by rememberUpdatedState(onTransformChanged)
    val scope = rememberCoroutineScope()
    fun centerOffset(point: Offset) = Offset(point.x - bounds.viewportWidth / 2f, point.y - bounds.viewportHeight / 2f)
    val transformState = rememberTransformableState { centroid, zoom, pan, _ ->
        interactions.zoomAnimation?.cancel()
        val focus = centerOffset(centroid)
        onTransformChanged(transform.transform(zoom, pan.x, pan.y, focus.x, focus.y, bounds))
        onInteraction()
    }
    fun animateZoom(tap: Offset?) {
        val start = currentTransform
        val focus = tap?.let(::centerOffset) ?: Offset.Zero
        val target = start.doubleTap(focus.x, focus.y, bounds)
        interactions.zoomAnimation?.cancel()
        interactions.zoomAnimation = scope.launch {
            animate(0f, 1f, animationSpec = tween(TheoriaMotion.MEDIUM_MS)) { fraction, _ ->
                currentOnTransformChanged(start.lerpTo(target, fraction))
            }
        }
    }
    val gestures = viewerMediaGestureModifier(
        mediaKey = key, active = controls.isActive && !layout.pictureInPicture, seekable = seekable,
        zoomed = transform.isZoomed, chromeVisible = controls.showControls, touchExplorationEnabled = layout.touchExploration,
        actions = ViewerMediaGestureActions(
            seek = { interactions.seek(it); onInteraction() },
            zoom = { tap -> animateZoom(tap); onInteraction() },
            toggleControls = onToggleChrome,
            showInfo = { onAction(ViewerAction.ShowMetadata); onInteraction() },
        ),
    )
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()
            .transformable(transformState, canPan = { transform.isZoomed }).then(gestures))
        if (controls.showControls && animated) Spacer(Modifier.fillMaxWidth().height(96.dp + LocalViewerPostStripInset.current))
    }
}
