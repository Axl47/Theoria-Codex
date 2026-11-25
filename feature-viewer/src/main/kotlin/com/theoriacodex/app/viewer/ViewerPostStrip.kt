package com.theoriacodex.app.viewer

import androidx.compose.runtime.getValue
import com.theoriacodex.app.ui.motion.TheoriaMotion
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import coil.compose.AsyncImage
import com.theoriacodex.app.media.isAnimatedPost
import com.theoriacodex.app.search.postMediaCount
import com.theoriacodex.app.ui.theme.TextOverMediaShadow
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlin.math.roundToInt
import kotlin.math.sign

internal const val VIEWER_PAGINATION_PREFETCH_RATIO = 0.8f

private val STRIP_TOP_PADDING = 13.dp
private val STRIP_BOTTOM_PADDING = 5.dp
private val STRIP_MAX_CELL = 46.dp
private val STRIP_MIN_GAP = 12.dp
private const val STRIP_MIN_SLOTS = 7

internal val VIEWER_POST_STRIP_HEIGHT = STRIP_TOP_PADDING + STRIP_MAX_CELL + STRIP_BOTTOM_PADDING
internal val VIEWER_POST_STRIP_CELL_CENTER = STRIP_BOTTOM_PADDING + STRIP_MAX_CELL / 2

private const val SELECTED_THUMBNAIL_GROWTH = 0.13f
private const val RESTING_THUMBNAIL_DIM = 0.28f

private val BADGE_STYLE = TextStyle(color = Color.White, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum", shadow = TextOverMediaShadow)
private const val BADGE_SCRIM = 0.3f

private val UNFOLDED_PAGE_GAP = 4.dp
private val UNFOLDED_PADDING = 4.dp
private val UNFOLDED_BACKDROP = Color.White.copy(alpha = 0.1f)

internal class ViewerStripPages(
    val postId: PostId,
    val items: List<ViewerMediaOverviewItem>,
    val selectedIndex: Int,
    val reversed: Boolean,
    val onSelect: (Int) -> Unit,
)

@Composable
internal fun ViewerPostStrip(
    posts: List<Post>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onLoadMore: () -> Unit = {},
    onInteraction: () -> Unit = {},
    openPages: ViewerStripPages? = null,
    chapters: Boolean = false,
    stripMarks: Map<PostId, ViewerStripMark> = emptyMap(),
) {
    val list = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
    RequestMorePostsNearEnd(list, posts.lastIndex, onLoadMore)
    ReportUserScroll(list, onInteraction)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val slots = viewerStripSlotCount(maxWidth)
        val cell = ((maxWidth - 32.dp) / slots).coerceIn(1.dp, STRIP_MAX_CELL)
        val gap = ((maxWidth - cell * slots) / (slots + 1)).coerceAtLeast(0.dp)
        val unfolded = openPages?.takeIf { it.items.size > 1 && posts.getOrNull(selectedIndex)?.id == it.postId }
        val alone = posts.size == 1
        val selectedWidth = unfolded?.let { viewerStripUnfoldedWidth(it.items.size, cell, gap, slots, alone) } ?: cell
        val cellPx = with(LocalDensity.current) { cell.toPx() }
        val selectedWidthPx = with(LocalDensity.current) { selectedWidth.toPx() }
        val centerPx = with(LocalDensity.current) { ((maxWidth - cell) / 2 - gap).toPx() }
        val motion = remember(list) { ViewerStripMotion(selectedIndex, selectedWidthPx) }
        CenterSelectedPost(list, motion, selectedIndex, selectedWidthPx, cellPx, centerPx, posts.lastIndex)
        LazyRow(
            state = list,
            contentPadding = PaddingValues(start = gap, end = gap, top = STRIP_TOP_PADDING, bottom = STRIP_BOTTOM_PADDING),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            itemsIndexed(posts, key = { _, post -> "${post.id.source}:${post.id.sourcePostId}" }) { index, post ->
                val width = { motion.width(index, cellPx) }
                val pages = rememberPagesWhileOpen(unfolded?.takeIf { index == selectedIndex }) { width() > cellPx + 0.5f }
                Box(Modifier.stripSlot(width, cell, clipPages = pages != null), contentAlignment = Alignment.Center) {
                    if (pages != null) {
                        val pagesWidth = viewerStripUnfoldedWidth(pages.items.size, cell, gap, slots, alone)
                        ViewerUnfoldedPages(post.id.source, pages, cell, onInteraction, Modifier.requiredWidth(pagesWidth).height(cell))
                    } else {
                        val mark = stripMarks[post.id]
                        val label = (mark?.label ?: post.title?.takeIf { chapters })?.let { "Open $it" } ?: viewerPostLabel(post)
                        val page = post.media.takeIf { chapters }?.let { it.getOrNull(mark?.page ?: 0) ?: it.firstOrNull() }?.takeIf { (it.localPath ?: it.url) != null }
                        ViewerPostThumbnail(post, index == selectedIndex, Modifier.size(cell), postMediaCount(post), label, mark?.badge, mark?.image ?: page ?: post.preview) { onSelect(index) }
                    }
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

private class ViewerStripMotion(selected: Int, selectedWidth: Float) {
    private var selected by mutableIntStateOf(selected)
    private var selectedWidth by mutableFloatStateOf(selectedWidth)
    private var fromWidths by mutableStateOf(emptyMap<Int, Float>())
    var progress by mutableFloatStateOf(1f)
    var following = false

    fun width(index: Int, cell: Float): Float = lerp(fromWidths[index] ?: cell, if (index == selected) selectedWidth else cell, progress)

    fun isAt(index: Int, width: Float): Boolean = selected == index && selectedWidth == width

    fun moveTo(index: Int, width: Float, cell: Float) {
        fromWidths = (fromWidths.keys + selected).associateWith { width(it, cell) }
        selected = index
        selectedWidth = width
        progress = 0f
    }
}

@Composable
private fun CenterSelectedPost(list: LazyListState, motion: ViewerStripMotion, index: Int, width: Float, cell: Float, center: Float, lastIndex: Int) {
    val last by rememberUpdatedState(lastIndex)
    LaunchedEffect(list) {
        list.interactionSource.interactions.collect { if (it is DragInteraction.Start) motion.following = false }
    }
    LaunchedEffect(list, index, width, center) {
        motion.following = true
        val offset = ((width - cell) / 2 - center).roundToInt()
        if (motion.isAt(index, width) && motion.progress == 1f) {
            list.scrollToItem(index, offset)
        } else {
            list.glideTo(motion, index, width, cell, center)
        }
        snapshotFlow { last }.drop(1).collect {
            if (motion.following && !list.isScrollInProgress) list.animateScrollToItem(index, offset)
        }
    }
}

private suspend fun LazyListState.glideTo(motion: ViewerStripMotion, index: Int, width: Float, cell: Float, center: Float) {
    val startingError = distanceFromCenter(index, cell + center * 2)
    if (!motion.isAt(index, width)) motion.moveTo(index, width, cell)
    val from = motion.progress
    animate(from, 1f, animationSpec = tween(TheoriaMotion.SCREEN_MS, easing = LinearOutSlowInEasing)) { value, _ ->
        motion.progress = value
        if (!isScrollInProgress) {
            requestScrollToItem(index, ((motion.width(index, cell) - cell) / 2 - center - startingError * (1f - value) / (1f - from)).roundToInt())
        }
    }
}

private fun LazyListState.distanceFromCenter(index: Int, centerSpan: Float): Float {
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    return if (item != null) item.offset + item.size / 2f - centerSpan / 2f else (index - firstVisibleItemIndex).sign * layoutInfo.viewportSize.width.toFloat()
}

@Composable
private fun rememberPagesWhileOpen(pages: ViewerStripPages?, isOpen: () -> Boolean): ViewerStripPages? {
    val last = remember { arrayOfNulls<ViewerStripPages>(1) }
    val latestIsOpen by rememberUpdatedState(isOpen)
    val open by remember { derivedStateOf { latestIsOpen() } }
    SideEffect { if (pages != null) last[0] = pages }
    return pages ?: last[0].takeIf { open }
}

private fun Modifier.stripSlot(width: () -> Float, cell: Dp, clipPages: Boolean): Modifier = this
    .layout { measurable, constraints ->
        val widthPx = width().roundToInt()
        val placeable = measurable.measure(constraints.copy(minWidth = widthPx, maxWidth = widthPx))
        layout(widthPx, placeable.height) { placeable.place(0, 0) }
    }
    .height(cell)
    .drawBehind {
        val opened = ((size.width - cell.toPx()) / cell.toPx()).coerceIn(0f, 1f)
        if (opened > 0f) {
            val bleed = UNFOLDED_PADDING.toPx()
            drawRoundRect(UNFOLDED_BACKDROP.copy(alpha = UNFOLDED_BACKDROP.alpha * opened), Offset(0f, -bleed), Size(size.width, size.height + bleed * 2), CornerRadius(12.dp.toPx()))
        }
    }
    .then(if (clipPages) Modifier.drawWithContent { clipRect(top = -size.height, bottom = size.height * 2) { this@drawWithContent.drawContent() } } else Modifier)

@Composable
private fun CenterItem(list: LazyListState, index: Int, offset: Int) {
    var centered by remember(list) { mutableStateOf(false) }
    LaunchedEffect(list, index, offset) {
        if (centered) list.animateScrollToItem(index, offset) else list.scrollToItem(index, offset)
        centered = true
    }
}

internal fun viewerStripSlotCount(width: Dp): Int {
    val fitting = ((width - STRIP_MIN_GAP) / (STRIP_MAX_CELL + STRIP_MIN_GAP)).toInt()
    val odd = if (fitting % 2 == 0) fitting - 1 else fitting
    return odd.coerceAtLeast(STRIP_MIN_SLOTS)
}

internal fun viewerStripUnfoldedWidth(pageCount: Int, cell: Dp, gap: Dp, slots: Int, alone: Boolean): Dp {
    val pages = cell * pageCount + UNFOLDED_PAGE_GAP * (pageCount - 1) + UNFOLDED_PADDING * 2
    val room = if (alone) slots else slots - 2
    return minOf(pages, cell * room + gap * (room - 1))
}

@Composable
private fun ViewerUnfoldedPages(source: SourceKey, pages: ViewerStripPages, cell: Dp, onInteraction: () -> Unit, modifier: Modifier) {
    val list = rememberLazyListState(initialFirstVisibleItemIndex = pages.selectedIndex)
    ReportUserScroll(list, onInteraction)
    BoxWithConstraints(modifier) {
        CenterItem(list, pages.selectedIndex, with(LocalDensity.current) { -((maxWidth - cell) / 2 - UNFOLDED_PADDING).roundToPx() })
        LazyRow(
            state = list,
            reverseLayout = pages.reversed,
            contentPadding = PaddingValues(horizontal = UNFOLDED_PADDING),
            horizontalArrangement = Arrangement.spacedBy(UNFOLDED_PAGE_GAP),
        ) {
            itemsIndexed(pages.items, key = { _, item -> item.mediaIndex }) { _, item ->
                ViewerThumbnailTile(
                    model = rememberViewerThumbnailRequest(source, item),
                    description = "Go to page ${item.mediaIndex + 1} of ${pages.items.size}",
                    selected = item.mediaIndex == pages.selectedIndex,
                    animated = item.kind.overviewBadgeLabel != null,
                    modifier = Modifier.size(cell),
                ) { pages.onSelect(item.mediaIndex) }
            }
        }
    }
}

@Composable
internal fun ViewerPostThumbnail(
    post: Post,
    selected: Boolean,
    modifier: Modifier = Modifier,
    pageCount: Int = 1,
    label: String = viewerPostLabel(post),
    badge: String? = null,
    image: ImageRef = post.preview,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    var failed by remember(image) { mutableStateOf(false) }
    val shown = if (failed) post.preview else image
    val location = shown.localPath ?: shown.url
    val page = shown != post.preview
    val request = remember(context, location, post.id.source, page) {
        location?.let { buildViewerImageRequest(context, it, post.id.source, staticAnimatedWebPFrame = true) }
            ?.let { if (page) it.newBuilder().transformations(PageTopCrop).build() else it }
    }
    val animated = remember(post) { isAnimatedPost(post) }
    ViewerThumbnailTile(request, label, selected, animated, modifier, pageCount, badge, onError = { failed = true }, onClick = onClick)
}

private fun viewerPostLabel(post: Post) = "Open post ${post.id.sourcePostId}"

@Composable
private fun ViewerThumbnailTile(
    model: Any?,
    description: String,
    selected: Boolean,
    animated: Boolean,
    modifier: Modifier = Modifier,
    pageCount: Int = 1,
    badge: String? = null,
    onError: (() -> Unit)? = null,
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
            AsyncImage(model, description, Modifier.fillMaxSize().clip(RoundedCornerShape(if (selected) 6.dp else 9.dp)), contentScale = ContentScale.Crop, onError = onError?.let { { _ -> it() } })
            if (animated) {
                Icon(
                    Icons.Default.PlayArrow,
                    "Animated",
                    Modifier.align(Alignment.Center).size(22.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape).padding(2.dp),
                    tint = Color.White,
                )
            }
            badge?.let { ThumbnailBadge(it) }
        }
    }
}

@Composable
private fun ThumbnailBadge(text: String) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = BADGE_SCRIM)).semantics { hideFromAccessibility() }, contentAlignment = Alignment.Center) {
        BasicText(
            text, Modifier.padding(horizontal = 3.dp), style = BADGE_STYLE, maxLines = 1, softWrap = false,
            autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = 15.sp),
        )
    }
}

private fun Modifier.thumbnailSurface(emphasis: State<Float>, pageCount: Int, shape: Shape): Modifier = this
    .graphicsLayer {
        val scale = 1f + SELECTED_THUMBNAIL_GROWTH * emphasis.value
        scaleX = scale
        scaleY = scale
    }
    .then(if (pageCount > 1) Modifier.drawBehind { drawPageStack(pageCount) } else Modifier)
    .clip(shape)
    .drawWithContent {
        drawContent()
        drawRect(Color.Black.copy(alpha = RESTING_THUMBNAIL_DIM * (1f - emphasis.value)))
    }
    .background(Color.White.copy(alpha = 0.12f))

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
