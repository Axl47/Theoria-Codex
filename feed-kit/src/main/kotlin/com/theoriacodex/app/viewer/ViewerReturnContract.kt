package com.theoriacodex.app.viewer

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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.geometry.Rect
import com.theoriacodex.app.ui.components.ProvideFeedViewport
import com.theoriacodex.domain.model.PostId
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

val LocalViewerReturnTransition = staticCompositionLocalOf<ViewerReturnTransition?> { null }
val LocalViewerReturnGrid = staticCompositionLocalOf<String?> { null }

sealed interface ViewerReturnShelfId {
    data class Related(val seedId: PostId) : ViewerReturnShelfId
    data class Saved(val codexId: String) : ViewerReturnShelfId
    data object RecentsTimeline : ViewerReturnShelfId
}

internal val LocalViewerReturnShelf = staticCompositionLocalOf<ViewerReturnShelfId?> { null }

data class ViewerReturnRequest(val gridId: String?, val postId: PostId, val shelfId: ViewerReturnShelfId? = null)

data class ViewerReturnCorners(
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

fun viewerReturnCorners(outline: Outline, titleFooter: Boolean): ViewerReturnCorners {
    val rounded = (outline as? Outline.Rounded)?.roundRect ?: return ViewerReturnCorners()
    return ViewerReturnCorners(rounded.topLeftCornerRadius, rounded.topRightCornerRadius,
        if (titleFooter) CornerRadius.Zero else rounded.bottomRightCornerRadius,
        if (titleFooter) CornerRadius.Zero else rounded.bottomLeftCornerRadius)
}

class ViewerReturnTransition {
    var retainedPostIds by mutableStateOf<Set<PostId>>(emptySet())
        private set

    fun retainPosts(postIds: Set<PostId>) {
        retainedPostIds = postIds
    }

    private var hasOrigin = false
    private var unclaimedPost: PostId? = null
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

    var mediaSettling by mutableStateOf(false)
        private set
    private val mediaCorners = mutableMapOf<PostId, ViewerReturnCorners>()
    private var positioned = false
    private var rowPositioned = false
    private val mediaBounds = mutableMapOf<PostId, Rect>()

    fun openedFrom(
        gridId: String?,
        bounds: Rect? = null,
        shelfId: ViewerReturnShelfId? = null,
        corners: ViewerReturnCorners = ViewerReturnCorners(),
        post: PostId,
    ) {
        clear()
        hasOrigin = true
        unclaimedPost = post
        originGridId = gridId
        originShelfId = shelfId
        openingBounds = bounds?.takeUnless { it.isEmpty }
        openingCorners = corners
    }

    fun launching(postIds: Collection<PostId>, keepsRoot: Boolean) {
        if (keepsRoot) return
        val tapped = unclaimedPost
        if (tapped != null && tapped in postIds) unclaimedPost = null else clear()
    }

    fun finishOpening() {
        openingBounds = null
    }

    fun begin(postId: PostId) {
        if (!hasOrigin) return
        openingBounds = null
        request = ViewerReturnRequest(originGridId, postId, originShelfId)
        mediaSettling = true
        targetBounds = null
        positioned = false
        rowPositioned = originShelfId == null
        mediaBounds.clear()
    }

    fun finishLanding() {
        mediaSettling = false
    }

    fun recordBounds(gridId: String?, postId: PostId, bounds: Rect, shelfId: ViewerReturnShelfId? = null, corners: ViewerReturnCorners = ViewerReturnCorners()) {
        val pending = request ?: return
        if (gridId == null || gridId != pending.gridId || postId != pending.postId || shelfId != pending.shelfId || bounds.isEmpty) return
        mediaBounds[postId] = bounds
        mediaCorners[postId] = corners
        if (positioned && rowPositioned) selectTarget(postId)
    }

    fun positioned(gridId: String, postId: PostId) {
        if (request?.gridId != gridId || request?.postId != postId) return
        positioned = true
        if (rowPositioned) selectTarget(postId)
    }

    fun shelfPositioned(gridId: String, postId: PostId, shelfId: ViewerReturnShelfId) {
        if (request != ViewerReturnRequest(gridId, postId, shelfId)) return
        rowPositioned = true
        if (positioned) selectTarget(postId)
    }

    private fun selectTarget(postId: PostId) {
        targetBounds = mediaBounds[postId]
        targetCorners = mediaCorners[postId] ?: ViewerReturnCorners()
    }

    fun clear() {
        hasOrigin = false
        unclaimedPost = null
        mediaSettling = false
        retainedPostIds = emptySet()
        openingCorners = ViewerReturnCorners()
        targetCorners = ViewerReturnCorners()
        mediaCorners.clear()
        request = null
        targetBounds = null
        originGridId = null
        originShelfId = null
        rowPositioned = false
        openingBounds = null
        positioned = false
        mediaBounds.clear()
    }
}

@Composable
fun retainPostsUntilViewerCloses(postIds: Set<PostId>): Set<PostId> {
    val viewerPostIds = LocalViewerReturnTransition.current?.retainedPostIds.orEmpty()
    return remember(postIds, viewerPostIds) { postIds + viewerPostIds }
}

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
        snapshotFlow { state.layoutInfo.visibleItemsInfo }.first { it.isNotEmpty() }
        val arrivedFrom = state.scrollWithinColumnsTo(index)
        val item = snapshotFlow { state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } }
            .filterNotNull().first()
        val layout = state.layoutInfo
        state.scrollBy(viewerReturnRevealScroll(item.offset.y, item.size.height,
            layout.viewportStartOffset + layout.beforeContentPadding,
            layout.viewportEndOffset - layout.afterContentPadding, arrivedFrom))
        withFrameNanos { }
        transition.positioned(gridId, request.postId)
    }
}

private suspend fun LazyStaggeredGridState.scrollWithinColumnsTo(index: Int): Float {
    var arrivedFrom = 0f
    repeat(MAX_VIEWER_RETURN_SCROLL_STEPS) {
        val visible = layoutInfo.visibleItemsInfo
        if (visible.isEmpty() || visible.any { it.index == index }) return arrivedFrom
        val viewport = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).toFloat()
        arrivedFrom = viewerReturnScrollDirection(index, visible.maxOf { it.index })
        if (scrollBy(arrivedFrom * viewport) == 0f) {
            scrollToItem(index)
            return arrivedFrom
        }
    }
    if (layoutInfo.visibleItemsInfo.none { it.index == index }) scrollToItem(index)
    return arrivedFrom
}

fun viewerReturnScrollDirection(targetIndex: Int, lastVisibleIndex: Int): Float =
    if (targetIndex < lastVisibleIndex) -1f else 1f

private const val MAX_VIEWER_RETURN_SCROLL_STEPS = 48

@Composable
internal fun ViewerReturnShelfEffect(shelfId: ViewerReturnShelfId, postIds: List<PostId>, state: LazyListState) {
    val transition = LocalViewerReturnTransition.current ?: return
    val gridId = LocalViewerReturnGrid.current ?: return
    val request = transition.request
    LaunchedEffect(request, gridId, shelfId, postIds) {
        if (request?.gridId != gridId || request.shelfId != shelfId) return@LaunchedEffect
        val index = postIds.indexOf(request.postId)
        if (index < 0) return@LaunchedEffect
        state.revealViewerReturnItem(index)
        transition.shelfPositioned(gridId, request.postId, shelfId)
    }
}

@Composable
fun ViewerReturnColumn(
    shelfIds: List<ViewerReturnShelfId?>,
    state: LazyListState,
    content: @Composable () -> Unit,
) = ViewerReturnColumn(state, shelfIds, { request -> shelfIds.indexOf(request.shelfId) }, content)

@Composable
fun ViewerReturnColumn(
    state: LazyListState,
    rowsKey: Any,
    rowOf: (ViewerReturnRequest) -> Int,
    content: @Composable () -> Unit,
) {
    val gridId = rememberSaveable { UUID.randomUUID().toString() }
    val transition = LocalViewerReturnTransition.current
    val request = transition?.request
    LaunchedEffect(request, gridId, rowsKey) {
        if (request?.gridId != gridId || request.shelfId == null) return@LaunchedEffect
        val index = rowOf(request)
        if (index < 0) return@LaunchedEffect
        state.revealViewerReturnItem(index)
        transition.positioned(gridId, request.postId)
    }
    CompositionLocalProvider(LocalViewerReturnGrid provides gridId) { ProvideFeedViewport(content) }
}

@Composable
fun ViewerReturnShelf(
    shelfId: ViewerReturnShelfId,
    postIds: List<PostId>,
    state: LazyListState,
    content: @Composable () -> Unit,
) {
    ViewerReturnShelfEffect(shelfId, postIds, state)
    CompositionLocalProvider(LocalViewerReturnShelf provides shelfId, content = content)
}

private suspend fun LazyListState.revealViewerReturnItem(index: Int) {
    val visible = snapshotFlow { layoutInfo.visibleItemsInfo }.first { it.isNotEmpty() }
    val arrivedFrom = when {
        visible.any { it.index == index } -> 0f
        index < visible.first().index -> -1f
        else -> 1f
    }
    if (arrivedFrom != 0f) scrollToItem(index)
    val item = snapshotFlow { layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } }
        .filterNotNull().first()
    val layout = layoutInfo
    scrollBy(viewerReturnRevealScroll(item.offset, item.size, layout.viewportStartOffset + layout.beforeContentPadding,
        layout.viewportEndOffset - layout.afterContentPadding, arrivedFrom))
    withFrameNanos { }
}

fun viewerReturnRevealScroll(
    itemStart: Int, itemSize: Int, visibleStart: Int, visibleEnd: Int, arrivedFrom: Float = 0f,
): Float {
    val itemEnd = itemStart + itemSize
    val toStart = itemStart - visibleStart
    val toEnd = itemEnd - visibleEnd
    return when {
        itemSize > visibleEnd - visibleStart -> toStart
        arrivedFrom > 0f -> toEnd
        arrivedFrom < 0f -> toStart
        toStart < 0 -> toStart
        toEnd > 0 -> toEnd
        else -> 0
    }.toFloat()
}
