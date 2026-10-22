package com.theoriacodex.app.viewer

import android.os.SystemClock
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
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
    levelControls: ViewerLevelControls? = null,
    returnProgress: Float = 0f,
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
        Box(
            Modifier.fillMaxSize().padding(if (layout.landscape || animated) 0.dp else 16.dp)
                .viewerReturnMedia(returnProgress, viewerAspectRatio(post)),
            contentAlignment = Alignment.Center,
        ) {
            ViewerMediaContent(
                post, media, key, controls.copy(seekJumpSerial = interactions.seekSerial, seekJumpDeltaMs = interactions.seekDelta),
                events, videoOptions, videoPositions, imageSession, pixivUgoiraClient, transformModifier, onRequestMediaRecovery,
            )
            FittedSeekJumpFeedbackOverlay(interactions.feedback, viewerAspectRatio(post), interactions::clearFeedback, Modifier.matchParentSize())
        }
        ViewerMediaGestureLayer(key, mediaCount, isVideo || isGif || isUgoira, animated, interactions, controls,
            transform, onTransformChanged, layout, onAction, onInteraction, onToggleChrome, onNavigate,
            levelControls.takeIf { isVideo })
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
    levelControls: ViewerLevelControls?,
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
            .viewerMediaSwipe(
                key, transform.isZoomed, mediaCount, layout.invertGalleryDirection, onNavigate,
                enabled = controls.isActive && !layout.pictureInPicture && !layout.touchExploration,
                levelControls = levelControls,
            )
            .transformable(transformState, canPan = { transform.isZoomed }).then(gestures))
        if (controls.showControls && animated) Spacer(Modifier.fillMaxWidth().height(96.dp))
    }
}
