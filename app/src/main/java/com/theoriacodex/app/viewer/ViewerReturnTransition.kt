package com.theoriacodex.app.viewer

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.rememberSaveable
import java.util.UUID
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
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

internal sealed interface ViewerReturnShelfId {
    data class Related(val seedId: PostId) : ViewerReturnShelfId
    data class Saved(val codexId: String) : ViewerReturnShelfId
}

internal val LocalViewerReturnShelf = staticCompositionLocalOf<ViewerReturnShelfId?> { null }

internal data class ViewerReturnRequest(val gridId: String?, val postId: PostId, val shelfId: ViewerReturnShelfId? = null)

/** Keep both destinations alive while the media moves between its card and fullscreen bounds. */
@Composable
internal fun ViewerReturnLifetime(scope: AnimatedVisibilityScope) {
    scope.transition.animateFloat(
        transitionSpec = {
            tween(if (targetState == EnterExitState.PostExit) VIEWER_RETURN_HOLD_MS else VIEWER_RETURN_DURATION_MS)
        }, label = "Viewer media transition lifetime",
    ) { state -> if (state == EnterExitState.Visible) 0f else 1f }
}

/** Per-corner feed clipping in window pixels; captions leave the media's lower edge square. */
internal data class ViewerReturnCorners(
    val topLeft: CornerRadius = CornerRadius.Zero,
    val topRight: CornerRadius = CornerRadius.Zero,
    val bottomRight: CornerRadius = CornerRadius.Zero,
    val bottomLeft: CornerRadius = CornerRadius.Zero,
) {
    fun roundRect(bounds: Rect, progress: Float): RoundRect {
        val fraction = progress.coerceIn(0f, 1f)
        fun scaled(corner: CornerRadius) = CornerRadius(corner.x * fraction, corner.y * fraction)
        return RoundRect(bounds, scaled(topLeft), scaled(topRight), scaled(bottomRight), scaled(bottomLeft))
    }
}

internal fun viewerReturnCorners(outline: Outline, titleFooter: Boolean): ViewerReturnCorners {
    val rounded = (outline as? Outline.Rounded)?.roundRect ?: return ViewerReturnCorners()
    return ViewerReturnCorners(rounded.topLeftCornerRadius, rounded.topRightCornerRadius,
        if (titleFooter) CornerRadius.Zero else rounded.bottomRightCornerRadius,
        if (titleFooter) CornerRadius.Zero else rounded.bottomLeftCornerRadius)
}

/** Transient geometry shared by the viewer and its originating feed. */
internal class ViewerReturnTransition {
    private var originGridId: String? = null
    private var originShelfId: ViewerReturnShelfId? = null
    var openingBounds by mutableStateOf<Rect?>(null)
        private set
    var request by mutableStateOf<ViewerReturnRequest?>(null)
        private set
    var targetBounds by mutableStateOf<Rect?>(null)
        private set
    var openingCorners by mutableStateOf(ViewerReturnCorners())
        private set
    var targetCorners by mutableStateOf(ViewerReturnCorners())
        private set
    private val mediaCorners = mutableMapOf<PostId, ViewerReturnCorners>()
    private var centered = false
    private var rowCentered = false
    private val mediaBounds = mutableMapOf<PostId, Rect>()

    fun openedFrom(gridId: String?, bounds: Rect? = null, shelfId: ViewerReturnShelfId? = null, corners: ViewerReturnCorners = ViewerReturnCorners()) {
        clear()
        originGridId = gridId
        originShelfId = shelfId
        openingBounds = bounds?.takeUnless { it.isEmpty }
        openingCorners = corners
    }

    fun finishOpening() {
        openingBounds = null
    }

    fun begin(postId: PostId) {
        openingBounds = null
        request = ViewerReturnRequest(originGridId, postId, originShelfId)
        targetBounds = null
        centered = false
        rowCentered = originShelfId == null
        mediaBounds.clear()
    }

    fun recordBounds(gridId: String?, postId: PostId, bounds: Rect, shelfId: ViewerReturnShelfId? = null, corners: ViewerReturnCorners = ViewerReturnCorners()) {
        val pending = request ?: return
        if (gridId == null || gridId != pending.gridId || postId != pending.postId || shelfId != pending.shelfId || bounds.isEmpty) return
        mediaBounds[postId] = bounds
        mediaCorners[postId] = corners
        if (centered && rowCentered) selectTarget(postId)
    }

    fun centered(gridId: String, postId: PostId) {
        if (request?.gridId != gridId || request?.postId != postId) return
        centered = true
        if (rowCentered) selectTarget(postId)
    }

    fun shelfCentered(gridId: String, postId: PostId, shelfId: ViewerReturnShelfId) {
        if (request != ViewerReturnRequest(gridId, postId, shelfId)) return
        rowCentered = true
        if (centered) selectTarget(postId)
    }

    private fun selectTarget(postId: PostId) {
        targetBounds = mediaBounds[postId]
        targetCorners = mediaCorners[postId] ?: ViewerReturnCorners()
    }

    fun clear() {
        openingCorners = ViewerReturnCorners()
        targetCorners = ViewerReturnCorners()
        mediaCorners.clear()
        request = null
        targetBounds = null
        originGridId = null
        originShelfId = null
        rowCentered = false
        openingBounds = null
        centered = false
        mediaBounds.clear()
    }
}

/** Runs after the underlying destination returns to composition, including offscreen posts. */
@Composable
internal fun ViewerReturnGridEffect(
    gridId: String, postIds: List<PostId?>, state: LazyStaggeredGridState, shelfIds: List<ViewerReturnShelfId?> = emptyList(),
) {
    val transition = LocalViewerReturnTransition.current ?: return
    val request = transition.request
    LaunchedEffect(request, gridId, postIds, shelfIds) {
        if (request?.gridId != gridId) return@LaunchedEffect
        val index = request.shelfId?.let(shelfIds::indexOf) ?: postIds.indexOf(request.postId)
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

/** Carousel media needs both its vertical shelf and horizontal card positioned before landing. */
@Composable
internal fun ViewerReturnShelfEffect(shelfId: ViewerReturnShelfId, postIds: List<PostId>, state: LazyListState) {
    val transition = LocalViewerReturnTransition.current ?: return
    val gridId = LocalViewerReturnGrid.current ?: return
    val request = transition.request
    LaunchedEffect(request, gridId, shelfId, postIds) {
        if (request?.gridId != gridId || request.shelfId != shelfId) return@LaunchedEffect
        val index = postIds.indexOf(request.postId)
        if (index < 0) return@LaunchedEffect
        state.centerViewerReturnItem(index)
        transition.shelfCentered(gridId, request.postId, shelfId)
    }
}

/** Saved's vertical overview participates in the same two-axis return as a related shelf. */
@Composable
internal fun ViewerReturnColumn(
    shelfIds: List<ViewerReturnShelfId?>,
    state: LazyListState,
    content: @Composable () -> Unit,
) {
    val gridId = rememberSaveable { UUID.randomUUID().toString() }
    val transition = LocalViewerReturnTransition.current
    val request = transition?.request
    LaunchedEffect(request, gridId, shelfIds) {
        if (request?.gridId != gridId || request.shelfId == null) return@LaunchedEffect
        val index = shelfIds.indexOf(request.shelfId)
        if (index < 0) return@LaunchedEffect
        state.centerViewerReturnItem(index)
        transition.centered(gridId, request.postId)
    }
    CompositionLocalProvider(LocalViewerReturnGrid provides gridId, content = content)
}

@Composable
internal fun ViewerReturnShelf(
    shelfId: ViewerReturnShelfId,
    postIds: List<PostId>,
    state: LazyListState,
    content: @Composable () -> Unit,
) {
    ViewerReturnShelfEffect(shelfId, postIds, state)
    CompositionLocalProvider(LocalViewerReturnShelf provides shelfId, content = content)
}

/** LazyRow and LazyColumn expose offsets along their own scroll axis. */
private suspend fun LazyListState.centerViewerReturnItem(index: Int) {
    scrollToItem(index)
    val item = snapshotFlow { layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } }
        .filterNotNull().first()
    val layout = layoutInfo
    scrollBy(viewerReturnCenterScroll(item.offset, item.size, layout.viewportStartOffset, layout.viewportEndOffset))
    withFrameNanos { }
}

internal fun viewerReturnCenterScroll(itemTop: Int, itemHeight: Int, viewportStart: Int, viewportEnd: Int): Float =
    itemTop + itemHeight / 2f - (viewportStart + viewportEnd) / 2f

/** One progress value describes both directions: zero is fullscreen and one is the feed card. */
@Composable
internal fun rememberViewerReturnProgress(): Float {
    val transition = LocalViewerReturnTransition.current
    val request = transition?.request
    val progress = remember { Animatable(if (transition?.openingBounds != null) 1f else 0f) }
    LaunchedEffect(request) {
        if (request == null) {
            if (transition?.openingBounds != null) {
                progress.animateTo(0f, tween(VIEWER_RETURN_DURATION_MS))
                transition.finishOpening()
            }
            return@LaunchedEffect
        }
        progress.snapTo(0f)
        withTimeoutOrNull(VIEWER_RETURN_TARGET_WAIT_MS) {
            snapshotFlow { transition.targetBounds }.filterNotNull().first()
        }
        progress.animateTo(1f, tween(if (transition.targetBounds == null) 180 else VIEWER_RETURN_DURATION_MS))
    }
    return progress.value
}

/** Uniform scale plus an animated crop preserves media proportions when the card has a different aspect ratio. */
@Composable
internal fun Modifier.viewerReturnMedia(progress: Float, aspectRatio: Float?): Modifier {
    val transition = LocalViewerReturnTransition.current ?: return this
    if (transition.request == null && transition.openingBounds == null) return this
    var windowBounds by remember { mutableStateOf(Rect.Zero) }
    val closing = transition.request != null
    val target = remember(transition.request, progress > 0f) {
        if (closing) transition.targetBounds else transition.openingBounds
    }
    val geometry = viewerReturnGeometry(windowBounds, target, aspectRatio, progress)
    val corners = if (closing) transition.targetCorners else transition.openingCorners
    return onGloballyPositioned {
        if (windowBounds.isEmpty || progress == 0f) windowBounds = it.boundsInWindow(clipBounds = false)
    }
        .drawWithContent {
            if (closing && progress >= 1f) return@drawWithContent
            if (progress <= 0f) {
                drawContent()
                return@drawWithContent
            }
            val path = Path().apply { addRoundRect(corners.roundRect(geometry.crop, progress)) }
            clipPath(path) { this@drawWithContent.drawContent() }
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
    if (target == null) return ViewerReturnGeometry(local, 1f, 0f, source.height * progress.coerceIn(0f, 1f))
    val destination = target.translate(-source.topLeft)
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
