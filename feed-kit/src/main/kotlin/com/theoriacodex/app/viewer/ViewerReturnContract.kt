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
import com.theoriacodex.domain.model.PostId
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

val LocalViewerReturnTransition = staticCompositionLocalOf<ViewerReturnTransition?> { null }
val LocalViewerReturnGrid = staticCompositionLocalOf<String?> { null }

sealed interface ViewerReturnShelfId {
    data class Related(val seedId: PostId) : ViewerReturnShelfId
    data class Saved(val codexId: String) : ViewerReturnShelfId
    data class ForYou(val sectionId: String) : ViewerReturnShelfId
    /** Every row of the Recents timeline, since viewing a post can regroup them; rows are found by post. */
    data object RecentsTimeline : ViewerReturnShelfId
}

internal val LocalViewerReturnShelf = staticCompositionLocalOf<ViewerReturnShelfId?> { null }

data class ViewerReturnRequest(val gridId: String?, val postId: PostId, val shelfId: ViewerReturnShelfId? = null)

/** Per-corner feed clipping in window pixels; captions leave the media's lower edge square. */
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

/** Transient geometry shared by the viewer and its originating feed. */
class ViewerReturnTransition {
    var retainedPostIds by mutableStateOf<Set<PostId>>(emptySet())
        private set

    fun retainPosts(postIds: Set<PostId>) {
        retainedPostIds = postIds
    }

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

    /** True while closing media is still moving, so the returning feed can hold its heaviest startup work. */
    var mediaSettling by mutableStateOf(false)
        private set
    private val mediaCorners = mutableMapOf<PostId, ViewerReturnCorners>()
    private var positioned = false
    private var rowPositioned = false
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

    /** The originating list has brought the returning card fully on screen (see [viewerReturnRevealScroll]). */
    fun positioned(gridId: String, postId: PostId) {
        if (request?.gridId != gridId || request?.postId != postId) return
        positioned = true
        if (rowPositioned) selectTarget(postId)
    }

    /** The card's horizontal shelf has brought it fully on screen; shelf returns need both axes. */
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

/** Hide policies resume only after ViewerRoute is disposed at the end of its exit animation. */
@Composable
fun retainPostsUntilViewerCloses(postIds: Set<PostId>): Set<PostId> {
    val viewerPostIds = LocalViewerReturnTransition.current?.retainedPostIds.orEmpty()
    return remember(postIds, viewerPostIds) { postIds + viewerPostIds }
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

/**
 * Reaches an offscreen card through relative scrolls, including one the Viewer swiped to. Jumping
 * with scrollToItem lines every column up at the target, which later forces a reshuffle. Returns the
 * direction of the last step (-1 up, 1 down), or 0 when the card was already in the viewport.
 */
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

/**
 * Cards fill columns in index order, so a later card being on screen puts an absent target above the
 * viewport, even when a tall earlier card in another column is still visible.
 */
fun viewerReturnScrollDirection(targetIndex: Int, lastVisibleIndex: Int): Float =
    if (targetIndex < lastVisibleIndex) -1f else 1f

/** A far target falls back to a jump instead of composing an unbounded number of cards. */
private const val MAX_VIEWER_RETURN_SCROLL_STEPS = 48

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
        state.revealViewerReturnItem(index)
        transition.shelfPositioned(gridId, request.postId, shelfId)
    }
}

/** Vertical overviews participate in the same two-axis return as a related shelf. */
@Composable
fun ViewerReturnColumn(
    shelfIds: List<ViewerReturnShelfId?>,
    state: LazyListState,
    content: @Composable () -> Unit,
) = ViewerReturnColumn(state, shelfIds, { request -> shelfIds.indexOf(request.shelfId) }, content)

/** [rowOf] finds the column item holding a request; [rowsKey] changes whenever that answer could. */
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
    CompositionLocalProvider(LocalViewerReturnGrid provides gridId, content = content)
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

/** LazyRow and LazyColumn expose offsets along their own scroll axis; an offscreen item is jumped to first. */
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

/**
 * How far a list scrolls so the returning card is fully visible, moving it as little as possible
 * (user decision 2026-11-16: keep the feed where it was rather than centring the card).
 *
 * "Visible" is `[visibleStart, visibleEnd]`: the list's viewport minus its content padding, in the
 * list's own item offsets. Content padding is where Compose lets chrome drawn over a list sit; top and
 * bottom bars and system insets in the current feeds are laid out outside the list, so a card under
 * them is already outside the viewport. Floating controls (FABs, Search's collapsed corner button)
 * do not count as covering.
 *
 * - Fully visible: 0, the list does not move.
 * - Larger than the visible span: align its start with [visibleStart].
 * - Otherwise move it just inside the nearer edge it crosses. [arrivedFrom] is the direction already
 *   travelled to bring an offscreen card into the layout (1 forward, -1 back, 0 none); it pins the
 *   card to the edge it entered from, so the total movement matches one minimal scroll.
 */
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
