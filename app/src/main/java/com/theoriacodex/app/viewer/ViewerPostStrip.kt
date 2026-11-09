package com.theoriacodex.app.viewer

import androidx.compose.runtime.getValue
import com.theoriacodex.app.ui.motion.TheoriaMotion
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.theoriacodex.app.media.isAnimatedPost
import com.theoriacodex.app.search.postMediaCount
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey

internal const val VIEWER_PAGINATION_PREFETCH_RATIO = 0.8f

internal val LocalViewerPostStripInset = staticCompositionLocalOf { 0.dp }

/** Room above a thumbnail for the cards stacked behind it, grown with the selection. */
private val STRIP_TOP_PADDING = 13.dp
private val STRIP_BOTTOM_PADDING = 5.dp
private val STRIP_MAX_CELL = 46.dp

/** The strip's height, which the player and its gestures keep clear of. */
internal val VIEWER_POST_STRIP_HEIGHT = STRIP_TOP_PADDING + STRIP_MAX_CELL + STRIP_BOTTOM_PADDING

/** Small enough that a grown thumbnail never overlaps its neighbours, in the strip or in the related shelf. */
private const val SELECTED_THUMBNAIL_GROWTH = 0.13f
private const val RESTING_THUMBNAIL_DIM = 0.28f

private val UNFOLDED_PAGE_GAP = 4.dp
private val UNFOLDED_PADDING = 4.dp
private val UNFOLDED_BACKDROP = Color.White.copy(alpha = 0.1f)

/** The post on screen and its pages, which the strip unfolds in that post's place. */
internal class ViewerStripPages(
    val postId: PostId,
    val items: List<ViewerMediaOverviewItem>,
    val selectedIndex: Int,
    val reversed: Boolean,
    val onSelect: (Int) -> Unit,
)

/**
 * Seven centered slots share equal gaps between thumbnails and at both viewport edges. When [openPages]
 * belongs to the selected post, that post unfolds in place, and its pages scroll inside the middle five
 * slots so the neighbouring posts stay in reach at both ends.
 */
@Composable
internal fun ViewerPostStrip(
    posts: List<Post>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onLoadMore: () -> Unit = {},
    onInteraction: () -> Unit = {},
    openPages: ViewerStripPages? = null,
) {
    val list = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
    RequestMorePostsNearEnd(list, posts.lastIndex, onLoadMore)
    ReportUserScroll(list, onInteraction)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val cell = ((maxWidth - 32.dp) / 7).coerceIn(1.dp, STRIP_MAX_CELL)
        val gap = ((maxWidth - cell * 7) / 8).coerceAtLeast(0.dp)
        val unfolded = openPages?.takeIf { it.items.size > 1 && posts.getOrNull(selectedIndex)?.id == it.postId }
        val selectedWidth = unfolded?.let { viewerStripUnfoldedWidth(it.items.size, cell, gap) } ?: cell
        CenterItem(list, selectedIndex, with(LocalDensity.current) { ((selectedWidth - cell) / 2).roundToPx() })
        LazyRow(
            state = list,
            contentPadding = PaddingValues(start = (maxWidth - cell) / 2, end = (maxWidth - cell) / 2, top = STRIP_TOP_PADDING, bottom = STRIP_BOTTOM_PADDING),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            itemsIndexed(posts, key = { _, post -> "${post.id.source}:${post.id.sourcePostId}" }) { index, post ->
                if (unfolded != null && index == selectedIndex) {
                    ViewerUnfoldedPages(post.id.source, unfolded, cell, onInteraction, Modifier.width(selectedWidth).height(cell))
                } else {
                    ViewerPostThumbnail(post, index == selectedIndex, Modifier.size(cell), pageCount = postMediaCount(post)) { onSelect(index) }
                }
            }
        }
    }
}

@Composable
private fun RequestMorePostsNearEnd(list: LazyListState, lastIndex: Int, onLoadMore: () -> Unit) {
    val loadMore by rememberUpdatedState(onLoadMore)
    LaunchedEffect(list, lastIndex) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .distinctUntilChanged().collect { lastVisible ->
                if (lastVisible != null && lastVisible >= lastIndex * VIEWER_PAGINATION_PREFETCH_RATIO) loadMore()
            }
    }
}

@Composable
private fun ReportUserScroll(list: LazyListState, onInteraction: () -> Unit) {
    val interact by rememberUpdatedState(onInteraction)
    LaunchedEffect(list) {
        snapshotFlow { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
            .collect { if (list.isScrollInProgress) interact() }
    }
}

/** Brings [index] to [offset] from the list's start: at once on first composition, animated after that. */
@Composable
private fun CenterItem(list: LazyListState, index: Int, offset: Int) {
    var centered by remember(list) { mutableStateOf(false) }
    LaunchedEffect(list, index, offset) {
        if (centered) list.animateScrollToItem(index, offset) else list.scrollToItem(index, offset)
        centered = true
    }
}

/** An unfolded post's width: enough for its pages, but never past the slots its two neighbours occupy. */
internal fun viewerStripUnfoldedWidth(pageCount: Int, cell: Dp, gap: Dp): Dp {
    val pages = cell * pageCount + UNFOLDED_PAGE_GAP * (pageCount - 1) + UNFOLDED_PADDING * 2
    return minOf(pages, cell * 5 + gap * 4)
}

/** One post's pages as a scroller of its own, holding the current page centered. Past either end, a drag carries on into the strip. */
@Composable
private fun ViewerUnfoldedPages(source: SourceKey, pages: ViewerStripPages, cell: Dp, onInteraction: () -> Unit, modifier: Modifier) {
    val list = rememberLazyListState(initialFirstVisibleItemIndex = pages.selectedIndex)
    ReportUserScroll(list, onInteraction)
    BoxWithConstraints(
        modifier.drawBehind {
            val bleed = UNFOLDED_PADDING.toPx()
            drawRoundRect(UNFOLDED_BACKDROP, Offset(0f, -bleed), Size(size.width, size.height + bleed * 2), CornerRadius(12.dp.toPx()))
        },
    ) {
        // A negative offset leaves the current page short of the viewport's start, which is what centers it.
        CenterItem(list, pages.selectedIndex, with(LocalDensity.current) { -((maxWidth - cell) / 2 - UNFOLDED_PADDING).roundToPx() })
        LazyRow(
            state = list,
            reverseLayout = pages.reversed,
            contentPadding = PaddingValues(horizontal = UNFOLDED_PADDING),
            horizontalArrangement = Arrangement.spacedBy(UNFOLDED_PAGE_GAP),
        ) {
            itemsIndexed(pages.items, key = { _, item -> item.mediaIndex }) { _, item ->
                ViewerThumbnailTile(
                    model = rememberViewerPosterRequest(source, item),
                    description = "Go to page ${item.mediaIndex + 1} of ${pages.items.size}",
                    selected = item.mediaIndex == pages.selectedIndex,
                    animated = item.kind.badgeLabel != null,
                    modifier = Modifier.size(cell),
                ) { pages.onSelect(item.mediaIndex) }
            }
        }
    }
}

/** [pageCount] above one stacks cards behind the thumbnail, so a multi-page post reads as one before it is opened. */
@Composable
internal fun ViewerPostThumbnail(post: Post, selected: Boolean, modifier: Modifier = Modifier, pageCount: Int = 1, onClick: () -> Unit) {
    val context = LocalContext.current
    val location = post.preview.localPath ?: post.preview.url
    val request = remember(context, location, post.id.source) {
        location?.let { buildViewerImageRequest(context, it, post.id.source, staticAnimatedWebPFrame = true) }
    }
    val animated = remember(post) { isAnimatedPost(post) }
    ViewerThumbnailTile(request, "Open post ${post.id.sourcePostId}", selected, animated, modifier, pageCount, onClick)
}

/** One tappable image in the Viewer's strips, whether it stands for a post or for one of a post's pages. */
@Composable
private fun ViewerThumbnailTile(
    model: Any?,
    description: String,
    selected: Boolean,
    animated: Boolean,
    modifier: Modifier = Modifier,
    pageCount: Int = 1,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(9.dp)
    val accent = LocalViewerAccent.current
    val emphasis = animateFloatAsState(if (selected) 1f else 0f, tween(TheoriaMotion.MEDIUM_MS), label = "Viewer thumbnail emphasis")
    Box(
        modifier.semantics {
            this.selected = selected
            if (pageCount > 1) stateDescription = "$pageCount pages"
        }.clickable(onClick = onClick),
    ) {
        Box(
            Modifier.fillMaxSize().thumbnailSurface(emphasis, pageCount, shape).then(if (selected) Modifier.border(2.dp, accent, shape).padding(3.dp) else Modifier),
        ) {
            AsyncImage(model, description, Modifier.fillMaxSize().clip(RoundedCornerShape(if (selected) 6.dp else 9.dp)), contentScale = ContentScale.Crop)
            if (animated) {
                Icon(
                    Icons.Default.PlayArrow,
                    "Animated",
                    Modifier.align(Alignment.Center).size(22.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape).padding(2.dp),
                    tint = Color.White,
                )
            }
        }
    }
}

/** Emphasis is drawn, not laid out: the strip's slots and its centering stay put as the selection moves. */
private fun Modifier.thumbnailSurface(emphasis: State<Float>, pageCount: Int, shape: Shape): Modifier = this
    .graphicsLayer {
        val scale = 1f + SELECTED_THUMBNAIL_GROWTH * emphasis.value
        scaleX = scale
        scaleY = scale
    }
    .then(if (pageCount > 1) Modifier.drawBehind { drawPageStack(pageCount) } else Modifier)
    .clip(shape)
    // A dim rather than transparency, so the cards stacked behind never show through the thumbnail.
    .drawWithContent {
        drawContent()
        drawRect(Color.Black.copy(alpha = RESTING_THUMBNAIL_DIM * (1f - emphasis.value)))
    }
    .background(Color.White.copy(alpha = 0.12f))

/** Blank cards peeking above a thumbnail: one for two pages, two for more. They rise rather than spread, since the gaps between slots are too narrow. */
private fun DrawScope.drawPageStack(pageCount: Int) {
    val radius = CornerRadius(8.dp.toPx())
    fun card(inset: Dp, rise: Dp, alpha: Float) = drawRoundRect(
        Color.White.copy(alpha = alpha),
        Offset(inset.toPx(), -rise.toPx()),
        Size(size.width - inset.toPx() * 2, size.height),
        radius,
    )
    if (pageCount > 2) card(8.dp, 8.dp, 0.17f)
    card(4.dp, 4.dp, 0.34f)
}
