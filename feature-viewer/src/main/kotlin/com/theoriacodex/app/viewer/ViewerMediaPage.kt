package com.theoriacodex.app.viewer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.theoriacodex.app.ui.motion.TheoriaHaptics
import com.theoriacodex.app.ui.motion.TheoriaMotion
import com.theoriacodex.app.media.awaitsVideoStream
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.app.viewer.state.ViewerMediaError
import com.theoriacodex.app.viewer.state.ViewerMediaKey
import com.theoriacodex.app.viewer.state.ViewerPanel
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post

internal data class ViewerMediaLayout(
    /**
     * Whether animated and video media fill the page too; otherwise they sit below a portrait top gap. Still images
     * always fill the page, so a gallery's pages fill the screen width whatever source it came from.
     */
    val edgeToEdge: Boolean,
    val pictureInPicture: Boolean,
    val touchExploration: Boolean,
)

internal data class ViewerVideoOptions(
    val quality: com.theoriacodex.domain.model.VideoQuality,
    val height: Int?,
    /** The stream this media plays, for media with streams; see [com.theoriacodex.app.viewer.viewerPlayback]. */
    val streamLabel: String?,
    val terminalMessage: String?,
    val diagnosticsEnabled: Boolean,
    /** What an episode's stream recovery needs from the screen; used only by media with streams. */
    val episode: ViewerEpisodeOptions? = null,
)

/**
 * The screen's side of an episode's stream recovery ([ViewerEpisodeStreams]): its [attempts], whether the stream
 * the user picked by hand there ([pickedLabel]), when the page's links were looked up, and how to [fallBack] to another stream.
 */
internal class ViewerEpisodeOptions(
    val attempts: ViewerStreamAttempts,
    val pickedLabel: String?,
    val linksResolvedAtMs: Long?,
    val fallBack: (label: String) -> Unit,
)

/** Owns taps and pinches and selects a renderer; swipes and route state stay in ViewerScreen. */
@Composable
internal fun ViewerMediaPage(
    post: Post,
    media: ImageRef,
    mediaIndex: Int,
    controls: ViewerRendererControls,
    events: ViewerRendererEvents,
    transform: State<ViewerTransformState>,
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
    onRequestMediaRecovery: ViewerMediaRecoveryRequest?,
    levelControls: ViewerLevelControls? = null,
    returnProgress: () -> Float = { 0f },
    dismissDrag: ViewerDismissDrag? = null,
    onPassiveInteraction: () -> Unit = {},
) {
    val key = ViewerMediaKey(post.id, mediaIndex)
    val kind = remember(post, media, pixivUgoiraClient != null) { viewerRendererMediaKind(post, media, pixivUgoiraClient != null) }
    val animated = kind.animated
    val interactions = remember(key) { ViewerMediaInteractions() }
    val holdScrub = LocalViewerHoldScrub.current?.takeIf { controls.isActive }
    // A page that goes inactive or leaves mid-hold (NEXT mode advancing) never sees the release.
    DisposableEffect(holdScrub) { onDispose { holdScrub?.abandon() } }
    // The transform changes every pinch event, so it is read only while drawing, never in composition.
    val transformModifier = remember(transform) {
        Modifier.graphicsLayer {
            val current = transform.value
            scaleX = current.zoom
            scaleY = current.zoom
            translationX = current.panX
            translationY = current.panY
        }
    }
    // A video whose stream is still resolving shows its poster as a still; it keeps the gap so it doesn't shift on arrival.
    val playsMotion = animated || (mediaIndex == 0 && post.awaitsVideoStream)
    val topGap = if (playsMotion && !layout.edgeToEdge) VIEWER_ANIMATED_TOP_GAP else 0.dp
    var pageSize by remember { mutableStateOf(IntSize.Zero) }
    val transformAspectRatio = if (controls.fillScreen) null else viewerMediaAspectRatio(post, media)
    val overlayAspectRatio = if (controls.fillScreen) null else viewerAspectRatio(post)
    val transformBounds = with(LocalDensity.current) {
        viewerTransformBounds(pageSize.width.toFloat(), pageSize.height.toFloat(), transformAspectRatio)
    }
    Box(Modifier.fillMaxSize().padding(top = topGap).onSizeChanged { pageSize = it }) {
        Box(
            Modifier.fillMaxSize()
                .viewerReturnMedia(returnProgress, overlayAspectRatio)
                .viewerDismissDrag(dismissDrag, returnProgress),
            contentAlignment = Alignment.Center,
        ) {
            CompositionLocalProvider(LocalViewerMediaAspectRatio provides overlayAspectRatio, LocalViewerHoldScrub provides holdScrub) {
                ViewerMediaContent(
                    post, media, key, kind,
                    controls.copy(
                        seekJumpSerial = interactions.seekSerial,
                        seekJumpDeltaMs = interactions.seekDelta,
                        playbackRate = if (interactions.holdingSpeed) VIEWER_HOLD_PLAYBACK_RATE else controls.playbackRate,
                    ),
                    events, videoOptions, videoPositions, imageSession, pixivUgoiraClient, transformModifier, onRequestMediaRecovery,
                )
            }
            FittedSeekJumpFeedbackOverlay(interactions.feedback, overlayAspectRatio, interactions::clearFeedback, Modifier.matchParentSize())
        }
        ViewerMediaGestureLayer(key, kind.seekable, animated, interactions, holdScrub, controls,
            transform, transformBounds, onTransformChanged, layout, onAction, onInteraction, onToggleChrome, onPassiveInteraction)
        if (controls.isActive && !layout.pictureInPicture && levelControls != null) {
            ViewerLevelFeedbackOverlay(levelControls, Modifier.matchParentSize())
        }
        ViewerHoldSpeedLabel(interactions.holdingSpeed, Modifier.align(Alignment.TopCenter))
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
    var holdingSpeed by mutableStateOf(false)

    fun seek(delta: Long) {
        seekDelta = delta
        seekSerial += 1
        feedback = nextSeekJumpFeedback(feedback, delta, SystemClock.elapsedRealtime(), seekSerial)
    }

    fun clearFeedback(serial: Int) { if (feedback?.serial == serial) feedback = null }
}

private const val VIEWER_HOLD_PLAYBACK_RATE = 2f

/** Space above animated and video media in portrait, clear of the top bar. */
private val VIEWER_ANIMATED_TOP_GAP = 40.dp

@Composable
private fun ViewerHoldSpeedLabel(visible: Boolean, modifier: Modifier) {
    AnimatedVisibility(visible, modifier.padding(top = 56.dp), enter = fadeIn(tween(TheoriaMotion.SHORT_MS)), exit = fadeOut(tween(TheoriaMotion.SHORT_MS))) {
        Text(
            "2×", color = Color.White, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun ViewerMediaGestureLayer(
    key: ViewerMediaKey, seekable: Boolean, animated: Boolean,
    interactions: ViewerMediaInteractions, holdScrub: ViewerHoldScrub?, controls: ViewerRendererControls,
    transform: State<ViewerTransformState>, bounds: ViewerTransformBounds, onTransformChanged: (ViewerTransformState) -> Unit,
    layout: ViewerMediaLayout, onAction: (ViewerAction) -> Unit, onInteraction: () -> Unit,
    onToggleChrome: () -> Unit, onPassiveInteraction: () -> Unit,
) {
    val zoomed by remember(transform) { derivedStateOf { transform.value.isZoomed } }
    val currentOnTransformChanged by rememberUpdatedState(onTransformChanged)
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    fun centerOffset(point: Offset) = Offset(point.x - bounds.viewportWidth / 2f, point.y - bounds.viewportHeight / 2f)
    val transformState = rememberTransformableState { centroid, zoom, pan, _ ->
        interactions.zoomAnimation?.cancel()
        val focus = centerOffset(centroid)
        onTransformChanged(transform.value.transform(zoom, pan.x, pan.y, focus.x, focus.y, bounds))
        onPassiveInteraction()
    }
    fun animateZoom(tap: Offset?) {
        val start = transform.value
        val focus = tap?.let(::centerOffset) ?: Offset.Zero
        val target = start.doubleTap(focus.x, focus.y, bounds)
        interactions.zoomAnimation?.cancel()
        interactions.zoomAnimation = scope.launch {
            animate(0f, 1f, animationSpec = tween(TheoriaMotion.MEDIUM_MS)) { fraction, _ ->
                currentOnTransformChanged(start.lerpTo(target, fraction))
            }
        }
    }
    val gestures = Modifier.viewerMediaGestures(
        mediaKey = key, active = controls.isActive && !layout.pictureInPicture, seekable = seekable,
        zoomed = zoomed, chromeVisible = controls.showControls, touchExplorationEnabled = layout.touchExploration,
        actions = ViewerMediaGestureActions(
            seek = { interactions.seek(it); onPassiveInteraction() },
            zoom = { tap -> animateZoom(tap); onPassiveInteraction() },
            toggleControls = onToggleChrome,
            showInfo = { onAction(ViewerAction.ShowPanel(ViewerPanel.INFO)); onInteraction() },
            hold = if (seekable) { holding ->
                if (holding && !interactions.holdingSpeed) haptics.performHapticFeedback(TheoriaHaptics.Hold)
                interactions.holdingSpeed = holding
                holdScrub?.holding = holding
            } else null,
        ),
    )
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()
            .transformable(transformState, canPan = { transform.value.isZoomed }).then(gestures))
        if (controls.showControls && animated) Spacer(Modifier.fillMaxWidth().height(VIEWER_TIMELINE_HEIGHT + LocalViewerTimelineInset.current))
    }
}
