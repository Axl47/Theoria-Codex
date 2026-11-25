package com.theoriacodex.app.viewer

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import com.theoriacodex.app.ui.components.rememberQuickReturn
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.ui.motion.TheoriaMotion
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.app.viewer.state.ViewerMediaError
import com.theoriacodex.app.viewer.state.ViewerMediaKey
import com.theoriacodex.app.viewer.state.ViewerPageState
import com.theoriacodex.app.viewer.state.ViewerPanel
import com.theoriacodex.domain.model.Post
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@Stable
internal class ViewerVerticalGalleryState(val list: LazyListState, pendingPageFraction: ViewerPageFraction? = null) {
    var pendingPageFraction by mutableStateOf(pendingPageFraction)
    var scale by mutableFloatStateOf(1f)
        private set
    var panX by mutableFloatStateOf(0f)
        private set
    var viewportWidth by mutableIntStateOf(0)
        private set

    val zoomed: Boolean get() = scale > 1.01f

    fun zoomAnchor(index: Int): Offset {
        val info = list.layoutInfo
        val page = info.visibleItemsInfo.firstOrNull { it.index == index }
        val y = page?.let { (maxOf(it.offset, info.viewportStartOffset) + minOf(it.offset + it.size, info.viewportEndOffset)) / 2f }
        return Offset(viewportWidth / 2f, y ?: (info.viewportSize.height / 2f))
    }

    fun resetZoom() {
        scale = 1f
        panX = 0f
    }

    fun transform(zoomBy: Float, focusX: Float, focusY: Float, panBy: Float) {
        val next = (scale * zoomBy).coerceIn(1f, MAX_ZOOM)
        val factor = next / scale
        val width = viewportWidth.toFloat()
        panX = ((panX + focusX) * factor - focusX - panBy).coerceIn(0f, width * (next - 1f))
        if (factor != 1f) {
            val offset = ((list.firstVisibleItemScrollOffset + focusY) * factor - focusY).roundToInt().coerceAtLeast(0)
            list.requestScrollToItem(list.firstVisibleItemIndex, offset)
        }
        scale = next
    }

    fun resize(width: Int) {
        viewportWidth = width
        panX = panX.coerceIn(0f, width * (scale - 1f))
    }

    private val scroll = ViewerVerticalScroll(atTop = { !list.canScrollBackward }, atEnd = { !list.canScrollForward })
    private val pendingScroll = ViewerVerticalScroll(atTop = { !list.canScrollBackward }, atEnd = { false })

    fun scrollGesture(pagesPending: Boolean): ViewerVerticalScroll = if (pagesPending) pendingScroll else scroll
}

@Composable
internal fun ViewerVerticalGallery(
    postPage: Int,
    pageState: ViewerPageState,
    frame: ViewerFrame,
    screen: ViewerScreenState,
    controls: ViewerControls,
    host: ViewerScreenHost,
    dismissDrag: ViewerDismissDrag?,
    readReturnProgress: () -> Float,
    onOpenNext: (() -> Unit)?,
) {
    val post = pageState.post
    val pages = pageState.media
    val isCurrentPost = postPage == frame.navigation.currentIndex
    val gallery = rememberVerticalGalleryState(pageState, frame.uiState.series, screen)
    val list = gallery.list
    DisposableEffect(post.id, gallery) {
        screen.verticalGalleries[post.id] = gallery
        onDispose { if (screen.verticalGalleries[post.id] === gallery) screen.verticalGalleries.remove(post.id) }
    }
    val shapes = remember(post.id, pageState.hasPendingGalleryMedia()) { HashMap<Int, MutableFloatState>() }
    val latestShape = remember(post.id) { object { var value = viewerAspectRatio(post) ?: DEFAULT_PAGE_SHAPE } }
    val painted = { index: Int -> !shapes.getOrPut(index) { mutableFloatStateOf(Float.NaN) }.floatValue.isNaN() }
    VerticalGalleryEffects(post, pageState, gallery, isCurrentPost, frame.uiState.series is ViewerSeries.Reading, painted, screen, host)
    val session = frame.uiState.session
    val selectedEvents = { key: ViewerMediaKey, generation: Long ->
        viewerRendererEvents(session, key, generation, host.onAction, onTimelineInteractionActiveChanged = {}, onDurationKnown = {})
    }
    val quickReturn = rememberQuickReturn { reveal -> if (isCurrentPost && reveal != frame.chromeVisible) controls.toggleChrome() }
    val (actions, assistive) = verticalGalleryActions(gallery, frame, controls, host)
    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize()
            .viewerDismissDrag(dismissDrag, readReturnProgress)
            .onSizeChanged { gallery.resize(it.width) }
            .nestedScroll(quickReturn)
            .verticalGalleryZoom(gallery, controls)
            .verticalGalleryTaps(gallery, controls, frame.touchExplorationEnabled, actions),
    ) {
        items(pages.size, key = { index -> index }) { index ->
            val placeholder = remember { latestShape.value }
            val painted = remember(shapes, index) { shapes.getOrPut(index) { mutableFloatStateOf(Float.NaN) } }
            val shape = painted.floatValue.takeUnless { it.isNaN() } ?: placeholder
            VerticalGalleryPage(
                post, pageState, index, shape, gallery, screen.imageSession,
                active = isCurrentPost && index == frame.selection.mediaIndex,
                selectedEvents = selectedEvents,
                sharedError = frame.uiState.mediaError?.takeIf { isCurrentPost && it.mediaKey == ViewerMediaKey(post.id, index) },
                onRetry = { host.onAction(ViewerAction.RetryMedia) },
                onPainted = { width, height ->
                    if (width > 0f && height > 0f) {
                        painted.floatValue = width / height
                        latestShape.value = width / height
                    }
                },
                onRequestMediaRecovery = host.onRequestMediaRecovery,
                assistive = assistive,
            )
        }
        if (onOpenNext != null) {
            item(key = "next") { VerticalGalleryNextCard(frame.posts.getOrNull(postPage + 1), Modifier.padding(bottom = viewerDockInset()), onOpenNext) }
        }
    }
}

@Composable
private fun rememberVerticalGalleryState(pageState: ViewerPageState, series: ViewerSeries?, screen: ViewerScreenState) = remember(pageState.post.id) {
    screen.verticalGalleryPositions.getOrPut(pageState.post.id) {
        ViewerVerticalGalleryState(
            LazyListState(pageState.selectedMediaIndex.coerceIn(0, maxOf(0, pageState.media.lastIndex))),
            (series as? ViewerSeries.Reading)?.pageFractions?.get(pageState.post.id),
        )
    }
}

@Composable
private fun verticalGalleryActions(
    gallery: ViewerVerticalGalleryState,
    frame: ViewerFrame,
    controls: ViewerControls,
    host: ViewerScreenHost,
): Pair<ViewerMediaGestureActions, SemanticsPropertyReceiver.() -> Unit> {
    val scope = rememberCoroutineScope()
    val selected = frame.selection.mediaIndex
    val actions = ViewerMediaGestureActions(
        seek = {},
        zoom = { tap -> scope.launch { gallery.animateZoomToggle(tap ?: gallery.zoomAnchor(selected)) } },
        toggleControls = { controls.toggleChrome() },
        showInfo = { host.onAction(ViewerAction.ShowPanel(ViewerPanel.INFO)) },
    )
    val zoomed by remember(gallery) { derivedStateOf { gallery.zoomed } }
    return actions to {
        onClick(label = "Media information") { actions.showInfo(); true }
        customActions = mediaAccessibilityActions(seekable = false, zoomed, frame.chromeVisible, frame.touchExplorationEnabled, actions)
    }
}

@Composable
private fun VerticalGalleryEffects(
    post: Post,
    pageState: ViewerPageState,
    gallery: ViewerVerticalGalleryState,
    isCurrentPost: Boolean,
    reportsPageOffset: Boolean,
    painted: (Int) -> Boolean,
    screen: ViewerScreenState,
    host: ViewerScreenHost,
) {
    val list = gallery.list
    val pageCount = pageState.media.size
    val selected = pageState.selectedMediaIndex
    val resuming = pageState.pendingMediaIndex != null
    var aligned by remember(list) { mutableStateOf(false) }
    VerticalGalleryScrollSelection(list, pageCount, selected, enabled = isCurrentPost && aligned, host)
    VerticalGalleryPageReport(post, gallery, selected, isCurrentPost && aligned, reportsPageOffset, painted, host)
    VerticalGalleryOffsetRestore(gallery, selected, isCurrentPost && aligned && !pageState.hasPendingGalleryMedia(), painted)
    LaunchedEffect(selected, pageCount, resuming) {
        if (resuming || selected !in 0 until pageCount) {
            aligned = false
            return@LaunchedEffect
        }
        if (!list.isScrollInProgress && list.focusedPage(pageCount) != selected) list.scrollToSelection(selected)
        aligned = true
    }
    VerticalGalleryPendingJump(post, list, pageCount, resuming, screen)
}

@Composable
private fun VerticalGalleryPageReport(
    post: Post,
    gallery: ViewerVerticalGalleryState,
    selected: Int,
    enabled: Boolean,
    reportsPageOffset: Boolean,
    painted: (Int) -> Boolean,
    host: ViewerScreenHost,
) {
    val list = gallery.list
    LaunchedEffect(post, selected, enabled, reportsPageOffset) {
        val report = host.onVisibleMediaChanged?.takeIf { enabled } ?: return@LaunchedEffect
        report(post, selected + 1, null)
        if (!reportsPageOffset) return@LaunchedEffect
        snapshotFlow { list.isScrollInProgress || gallery.pendingPageFraction != null }
            .filter { busy -> !busy }
            .collect { report(post, selected + 1, list.pageFraction(selected).takeUnless { it.isNaN() || !painted(selected) }) }
    }
}

@Composable
private fun VerticalGalleryOffsetRestore(gallery: ViewerVerticalGalleryState, selected: Int, ready: Boolean, painted: (Int) -> Boolean) {
    val list = gallery.list
    LaunchedEffect(gallery, ready, selected) {
        val (target, fraction) = gallery.pendingPageFraction?.takeIf { ready } ?: return@LaunchedEffect
        if (target != selected) {
            gallery.pendingPageFraction = null
            return@LaunchedEffect
        }
        fun position() = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
        val start = position()
        snapshotFlow { painted(target) || list.isScrollInProgress }.first { it }
        repeat(LAYOUT_FRAMES) { withFrameNanos { } }
        if (painted(target) && !list.isScrollInProgress && start == position()) list.scrollToPageFraction(target, fraction)
        gallery.pendingPageFraction = null
    }
}

@Composable
private fun VerticalGalleryScrollSelection(list: LazyListState, pageCount: Int, selected: Int, enabled: Boolean, host: ViewerScreenHost) {
    val focusedPage by remember(list, pageCount) { derivedStateOf { list.focusedPage(pageCount) } }
    val scrolling = list.isScrollInProgress
    var readerScrolled by remember(list) { mutableStateOf(false) }
    LaunchedEffect(focusedPage, scrolling, enabled, pageCount) {
        if (scrolling) readerScrolled = true
        if (!readerScrolled || !enabled || pageCount == 0) return@LaunchedEffect
        if (!scrolling) readerScrolled = false
        if (focusedPage != selected) host.onAction(ViewerAction.SelectMedia(focusedPage))
    }
}

@Composable
private fun VerticalGalleryPendingJump(post: Post, list: LazyListState, pageCount: Int, resuming: Boolean, screen: ViewerScreenState) {
    val pendingJump = screen.pendingMediaJumpByPost[post.id]
    LaunchedEffect(pendingJump, pageCount, resuming) {
        if (pendingJump == null || resuming || pageCount == 0) return@LaunchedEffect
        list.scrollToSelection(pendingJump.coerceIn(0, pageCount - 1))
        screen.pendingMediaJumpByPost.remove(post.id)
    }
}

private fun LazyListState.focusedPage(pageCount: Int): Int {
    if (pageCount <= 0) return 0
    val info = layoutInfo
    if (info.totalItemsCount == 0) return firstVisibleItemIndex.coerceIn(0, pageCount - 1)
    if (!canScrollBackward) return 0
    if (!canScrollForward) return pageCount - 1
    val line = selectionLine()
    val crossing = info.visibleItemsInfo.firstOrNull { it.offset <= line && it.offset + it.size > line }?.index
    return (crossing ?: firstVisibleItemIndex).coerceIn(0, pageCount - 1)
}

private fun LazyListState.pageFraction(index: Int): Float {
    val page = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.takeIf { it.size > 0 } ?: return Float.NaN
    val reached = ((selectionLine() - page.offset).toFloat() / page.size).coerceIn(0f, 1f)
    return (reached * PAGE_OFFSET_STEPS).roundToInt() / PAGE_OFFSET_STEPS
}

private suspend fun LazyListState.scrollToPageFraction(index: Int, fraction: Float) {
    scrollToItem(index)
    val page = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    scrollToItem(index, (layoutInfo.viewportStartOffset + fraction * page.size - selectionLine()).roundToInt().coerceIn(0, page.size))
}

private fun LazyListState.selectionLine(): Int = with(layoutInfo) { viewportStartOffset + (viewportEndOffset - viewportStartOffset) / 3 }

private suspend fun LazyListState.scrollToSelection(index: Int) {
    scrollToItem(index)
    val page = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val gap = selectionLine() - (page.offset + page.size)
    if (gap >= 0) scrollBy(-(gap + 1).toFloat())
}

@Composable
private fun VerticalGalleryPage(
    post: Post,
    pageState: ViewerPageState,
    index: Int,
    shape: Float,
    gallery: ViewerVerticalGalleryState,
    imageSession: ViewerImageSession,
    active: Boolean,
    selectedEvents: (ViewerMediaKey, Long) -> ViewerRendererEvents,
    sharedError: ViewerMediaError?,
    onRetry: () -> Unit,
    onPainted: (Float, Float) -> Unit,
    onRequestMediaRecovery: ViewerMediaRecoveryRequest?,
    assistive: SemanticsPropertyReceiver.() -> Unit,
) {
    val media = pageState.media[index]
    val key = ViewerMediaKey(post.id, index)
    val generation = media.loadGeneration
    var painted by remember(key, generation, media.ref) { mutableStateOf(false) }
    var failed by remember(key, generation, media.ref) { mutableStateOf<String?>(null) }
    var attempt by remember(key, generation, media.ref) { mutableIntStateOf(0) }
    val events = remember(key, generation, media.ref) {
        viewerRendererEvents(
            session = null, key = key, generation = generation, onAction = {}, onTimelineInteractionActiveChanged = {}, onDurationKnown = {},
        ).copy(onReady = { painted = true; failed = null }, onError = { failed = it })
    }
    LaunchedEffect(active, painted, failed, generation) {
        if (!active) return@LaunchedEffect
        val selected = selectedEvents(key, generation)
        if (painted) selected.onReady() else failed?.let(selected.onError)
    }
    Box(
        Modifier.fillMaxWidth()
            .layout { measurable, constraints ->
                val width = (constraints.maxWidth * gallery.scale).roundToInt()
                val placeable = measurable.measure(Constraints.fixedWidth(width))
                layout(constraints.maxWidth, placeable.height) { placeable.place(-gallery.panX.roundToInt(), 0) }
            }
            .semantics {
                contentDescription = "Page ${index + 1}"
                if (active) assistive()
            },
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(shape), contentAlignment = Alignment.Center) {
            key(attempt) {
                ViewerImageRenderer(
                    post = post, media = media.ref, key = key, animated = false,
                    modifier = Modifier.fillMaxSize(), mediaModifier = Modifier,
                    controls = VERTICAL_PAGE_CONTROLS.copy(loadGeneration = generation),
                    events = events, imageSession = imageSession,
                    onImageRecovery = onRequestMediaRecovery?.let { recovery -> recovery::ask },
                    onPainted = { size -> onPainted(size.width, size.height) },
                    spinnerModifier = Modifier.onVisiblePart(gallery.list, index),
                    fillWidth = true,
                )
            }
            (sharedError ?: failed?.let { ViewerMediaError.Recoverable(key, it) })?.let { error ->
                Box(Modifier.onVisiblePart(gallery.list, index)) {
                    ViewerMediaErrorOverlay(error) { if (sharedError != null) onRetry() else { failed = null; attempt += 1 } }
                }
            }
        }
    }
}

private fun Modifier.onVisiblePart(list: LazyListState, index: Int) = graphicsLayer {
    val viewport = list.layoutInfo
    val page = viewport.visibleItemsInfo.firstOrNull { it.index == index } ?: return@graphicsLayer
    translationY = visiblePartShift(
        page.offset, page.size, viewport.viewportStartOffset, viewport.viewportEndOffset,
        reach = size.height / 2 + STATUS_EDGE_MARGIN.toPx(),
    )
}

internal fun visiblePartShift(pageOffset: Int, pageSize: Int, viewportStart: Int, viewportEnd: Int, reach: Float): Float {
    if (pageSize <= reach * 2) return 0f
    val shownTop = maxOf(viewportStart, pageOffset) - pageOffset
    val shownBottom = minOf(viewportEnd, pageOffset + pageSize) - pageOffset
    return ((shownTop + shownBottom) / 2f).coerceIn(reach, pageSize - reach) - pageSize / 2f
}

@Composable
private fun VerticalGalleryNextCard(next: Post?, modifier: Modifier, onOpen: () -> Unit) {
    next ?: return
    val context = LocalContext.current
    val location = next.preview.localPath ?: next.preview.url
    val request = remember(context, location, next.id.source) { location?.let { buildViewerImageRequest(context, it, next.id.source) } }
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp)
            .clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onOpen).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(request, null, Modifier.size(width = 48.dp, height = 64.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
        Column(Modifier.weight(1f)) {
            Text("Next", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
            Text(
                next.title ?: next.id.source.displayName(), color = Color.White, style = MaterialTheme.typography.titleSmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun Modifier.verticalGalleryZoom(gallery: ViewerVerticalGalleryState, controls: ViewerControls) = pointerInput(gallery) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.filter { it.pressed }
            if (pressed.size >= 2) {
                val centroid = event.calculateCentroid(useCurrent = true)
                gallery.transform(event.calculateZoom(), centroid.x, centroid.y, event.calculatePan().x)
                event.changes.forEach { it.consume() }
                controls.markPassiveInteraction()
            } else if (gallery.zoomed && pressed.size == 1) {
                val change = pressed.single()
                val delta = change.positionChange()
                if (abs(delta.x) > abs(delta.y)) {
                    gallery.transform(1f, 0f, 0f, delta.x)
                    change.consume()
                }
            }
        } while (event.changes.any { it.pressed })
    }
}

private fun Modifier.verticalGalleryTaps(
    gallery: ViewerVerticalGalleryState,
    controls: ViewerControls,
    touchExplorationEnabled: Boolean,
    actions: ViewerMediaGestureActions,
) = pointerInput(gallery, touchExplorationEnabled) {
    detectTapGestures(
        onTap = { if (!touchExplorationEnabled) actions.toggleControls() },
        onDoubleTap = { tap ->
            actions.zoom(tap)
            controls.markPassiveInteraction()
        },
        onLongPress = {
            actions.showInfo()
            controls.markInteraction()
        },
    )
}

private suspend fun ViewerVerticalGalleryState.animateZoomToggle(tap: Offset) {
    var current = scale
    animate(current, if (zoomed) 1f else DOUBLE_TAP_ZOOM, animationSpec = tween(TheoriaMotion.MEDIUM_MS)) { value, _ ->
        transform(value / current, tap.x, tap.y, 0f)
        current = value
    }
}

private val VERTICAL_PAGE_CONTROLS = ViewerRendererControls(
    isActive = false, isPlaying = false, showControls = false, loopPlayback = false,
    playbackRate = 1f, restartRequest = 0L, loadGeneration = 0L,
)

private const val DEFAULT_PAGE_SHAPE = 0.7f
private const val PAGE_OFFSET_STEPS = 1000f
private const val LAYOUT_FRAMES = 2
private const val MAX_ZOOM = 4f
private const val DOUBLE_TAP_ZOOM = 2f

private val STATUS_EDGE_MARGIN = 24.dp
