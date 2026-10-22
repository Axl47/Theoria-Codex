package com.theoriacodex.app.viewer

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import com.theoriacodex.domain.model.PostId
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max

internal const val VIEWER_RETURN_DURATION_MS = 320
internal const val VIEWER_RETURN_HOLD_MS = 700
private const val VIEWER_RETURN_TARGET_WAIT_MS = 300L

internal val LocalViewerReturnTransition = staticCompositionLocalOf<ViewerReturnTransition?> { null }
internal val LocalViewerReturnGrid = staticCompositionLocalOf<String?> { null }

internal data class ViewerReturnRequest(val gridId: String?, val postId: PostId)

/** Register a real exit clock with Navigation without fading or scaling the entire destination. */
@Composable
internal fun ViewerReturnLifetime(scope: AnimatedVisibilityScope) {
    scope.transition.animateFloat(
        transitionSpec = { tween(VIEWER_RETURN_HOLD_MS) }, label = "Viewer return lifetime",
    ) { state -> if (state == EnterExitState.PostExit) 1f else 0f }
}

/** Transient geometry shared by the outgoing viewer and its original feed only. */
internal class ViewerReturnTransition {
    private var originGridId: String? = null
    var request by mutableStateOf<ViewerReturnRequest?>(null)
        private set
    var targetBounds by mutableStateOf<Rect?>(null)
        private set
    private var centered = false
    private val mediaBounds = mutableMapOf<PostId, Rect>()

    fun openedFrom(gridId: String?) {
        clear()
        originGridId = gridId
    }

    fun begin(postId: PostId) {
        request = ViewerReturnRequest(originGridId, postId)
        targetBounds = null
        centered = false
        mediaBounds.clear()
    }

    fun recordBounds(gridId: String?, postId: PostId, bounds: Rect) {
        val pending = request ?: return
        if (gridId == null || gridId != pending.gridId || postId != pending.postId || bounds.isEmpty) return
        mediaBounds[postId] = bounds
        if (centered) targetBounds = bounds
    }

    fun centered(gridId: String, postId: PostId) {
        if (request != ViewerReturnRequest(gridId, postId)) return
        centered = true
        targetBounds = mediaBounds[postId]
    }

    fun clear() {
        request = null
        targetBounds = null
        originGridId = null
        centered = false
        mediaBounds.clear()
    }
}

/** Runs after the underlying destination returns to composition, including offscreen posts. */
@Composable
internal fun ViewerReturnGridEffect(gridId: String, postIds: List<PostId?>, state: LazyStaggeredGridState) {
    val transition = LocalViewerReturnTransition.current ?: return
    val request = transition.request
    LaunchedEffect(request, gridId, postIds) {
        if (request?.gridId != gridId) return@LaunchedEffect
        val index = postIds.indexOf(request.postId)
        if (index < 0) return@LaunchedEffect
        state.scrollToItem(index)
        val item = snapshotFlow { state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } }
            .filterNotNull().first()
        val layout = state.layoutInfo
        state.scrollBy(viewerReturnCenterScroll(item.offset.y, item.size.height,
            layout.viewportStartOffset, layout.viewportEndOffset))
        withFrameNanos { }
        transition.centered(gridId, request.postId)
    }
}

internal fun viewerReturnCenterScroll(itemTop: Int, itemHeight: Int, viewportStart: Int, viewportEnd: Int): Float =
    itemTop + itemHeight / 2f - (viewportStart + viewportEnd) / 2f

/** The navigation exit keeps the renderer alive while the returned feed measures its target. */
@Composable
internal fun rememberViewerReturnProgress(): Float {
    val transition = LocalViewerReturnTransition.current
    val request = transition?.request
    val progress = remember { Animatable(0f) }
    LaunchedEffect(request) {
        progress.snapTo(0f)
        if (request == null) return@LaunchedEffect
        withTimeoutOrNull(VIEWER_RETURN_TARGET_WAIT_MS) {
            snapshotFlow { transition.targetBounds }.filterNotNull().first()
        }
        progress.animateTo(1f, tween(VIEWER_RETURN_DURATION_MS))
    }
    return progress.value
}

/** Uniform scale plus an animated crop preserves media proportions when the card has a different aspect ratio. */
@Composable
internal fun Modifier.viewerReturnMedia(progress: Float, aspectRatio: Float?): Modifier {
    val transition = LocalViewerReturnTransition.current ?: return this
    if (transition.request == null) return this
    var windowBounds by remember { mutableStateOf(Rect.Zero) }
    val target = remember(transition.request, progress > 0f) { transition.targetBounds }
    val geometry = viewerReturnGeometry(windowBounds, target, aspectRatio, progress)
    return onGloballyPositioned {
        if (progress == 0f) windowBounds = it.boundsInWindow(clipBounds = false)
    }
        .drawWithContent {
            if (progress >= 1f) return@drawWithContent
            if (progress <= 0f) {
                drawContent()
                return@drawWithContent
            }
            val crop = geometry.crop
            clipRect(crop.left, crop.top, crop.right, crop.bottom) { this@drawWithContent.drawContent() }
        }
        .graphicsLayer {
            transformOrigin = TransformOrigin(0.5f, 0.5f)
            scaleX = geometry.scale
            scaleY = geometry.scale
            translationX = geometry.translationX
            translationY = geometry.translationY
        }
}

internal data class ViewerReturnGeometry(val crop: Rect, val scale: Float, val translationX: Float, val translationY: Float)

internal fun viewerReturnGeometry(source: Rect, target: Rect?, aspectRatio: Float?, progress: Float): ViewerReturnGeometry {
    val local = Rect(0f, 0f, source.width, source.height)
    if (source.isEmpty || progress <= 0f) return ViewerReturnGeometry(local, 1f, 0f, 0f)
    val destination = target?.translate(-source.topLeft) ?: Rect(
        source.width * 0.35f, source.height * 0.35f, source.width * 0.65f, source.height * 0.65f,
    )
    val ratio = aspectRatio?.takeIf { it.isFinite() && it > 0f } ?: (source.width / source.height)
    val fittedWidth = minOf(source.width, source.height * ratio)
    val fittedHeight = fittedWidth / ratio
    val scale = max(destination.width / fittedWidth, destination.height / fittedHeight)
    val fraction = progress.coerceIn(0f, 1f)
    return ViewerReturnGeometry(
        crop = lerp(local, destination, fraction),
        scale = 1f + (scale - 1f) * fraction,
        translationX = (destination.center.x - local.center.x) * fraction,
        translationY = (destination.center.y - local.center.y) * fraction,
    )
}
