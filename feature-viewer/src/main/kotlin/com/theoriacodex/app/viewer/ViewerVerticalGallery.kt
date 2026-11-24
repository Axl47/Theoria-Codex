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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.semantics.contentDescription
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** One vertical gallery's list and zoom; the screen reads these to route gestures while it is the selected post. */
@Stable
internal class ViewerVerticalGalleryState(val list: LazyListState) {
    var scale by mutableFloatStateOf(1f)
        private set
    /** How far the zoomed pages are shifted left, in pixels; 0 shows their left edge. */
    var panX by mutableFloatStateOf(0f)
        private set
    var viewportWidth by mutableIntStateOf(0)
        private set

    val zoomed: Boolean get() = scale > 1.01f

    /** Returns the pages to fit, as starting a slideshow does for horizontal media. */
    fun resetZoom() {
        scale = 1f
        panX = 0f
    }

    /**
     * Scales around ([focusX], [focusY]) and pans by [panBy], keeping the pages inside the screen's width. Every page
     * grows by the same factor, so moving the list's offset in its first visible page by that factor keeps the row
     * under the fingers in place.
     */
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

    /** A narrower screen (rotation keeps this state) pulls the pan back inside the pages' new width. */
    fun resize(width: Int) {
        viewportWidth = width
        panX = panX.coerceIn(0f, width * (scale - 1f))
    }

    val scrollGesture = ViewerVerticalScroll(atTop = { !list.canScrollBackward }, atEnd = { !list.canScrollForward })
}

/**
 * A still-image gallery as one column. Pages fill the width (wider when zoomed), the page crossing the top third of
 * the screen is the selection, scrolling down hides the chrome and scrolling up brings it back, and a card after the
 * last page opens the next post. The screen-wide recognizer keeps horizontal swipes, which here change post.
 */
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
    val gallery = remember(post.id) {
        screen.verticalGalleryPositions.getOrPut(post.id) {
            ViewerVerticalGalleryState(LazyListState(pageState.selectedMediaIndex.coerceIn(0, maxOf(0, pages.lastIndex))))
        }
    }
    val list = gallery.list
    DisposableEffect(post.id, gallery) {
        screen.verticalGalleries[post.id] = gallery
        onDispose { if (screen.verticalGalleries[post.id] === gallery) screen.verticalGalleries.remove(post.id) }
    }
    // Width over height per page once painted; unpainted pages borrow the latest known shape, then the post's, and
    // keep the one they first laid out with, so a neighbour painting does not shift the pages on screen.
    val shapes = remember(post.id) { mutableStateMapOf<Int, Float>() }
    var latestShape by remember(post.id) { mutableFloatStateOf(viewerAspectRatio(post) ?: DEFAULT_PAGE_SHAPE) }
    VerticalGalleryEffects(post, pageState, gallery, isCurrentPost, screen, host)
    val scope = rememberCoroutineScope()
    val session = frame.uiState.session
    // The selected page reports readiness and failure to the Viewer, as a horizontal page does (slideshow holds wait on it).
    val selectedEvents = { key: ViewerMediaKey, generation: Long ->
        viewerRendererEvents(session, key, generation, host.onAction, onTimelineInteractionActiveChanged = {}, onDurationKnown = {})
    }
    val quickReturn = rememberQuickReturn { reveal -> if (isCurrentPost && reveal != frame.chromeVisible) controls.toggleChrome() }
    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize()
            .viewerDismissDrag(dismissDrag, readReturnProgress)
            .onSizeChanged { gallery.resize(it.width) }
            .nestedScroll(quickReturn)
            .verticalGalleryZoom(gallery, controls)
            .verticalGalleryTaps(gallery, controls, scope, host.onAction),
    ) {
        items(pages.size, key = { index -> index }) { index ->
            val placeholder = remember { latestShape }
            val shape = shapes[index] ?: placeholder
            VerticalGalleryPage(
                post, pageState, index, shape, gallery, screen.imageSession,
                active = isCurrentPost && index == frame.selection.mediaIndex,
                selectedEvents = selectedEvents,
                sharedError = frame.uiState.mediaError?.takeIf { isCurrentPost && it.mediaKey == ViewerMediaKey(post.id, index) },
                onRetry = { host.onAction(ViewerAction.RetryMedia) },
                onPainted = { width, height ->
                    if (width > 0f && height > 0f) {
                        shapes[index] = width / height
                        latestShape = width / height
                    }
                },
                onRequestMediaRecovery = host.onRequestMediaRecovery,
            )
        }
        if (onOpenNext != null) {
            // Clear of the dock, which shows again as the list is scrolled back up.
            item(key = "next") { VerticalGalleryNextCard(frame.posts.getOrNull(postPage + 1), Modifier.padding(bottom = viewerDockInset()), onOpenNext) }
        }
    }
}

/** Keeps the selection and the list in step: scrolling selects, and a page picked elsewhere scrolls into place. */
@Composable
private fun VerticalGalleryEffects(
    post: Post,
    pageState: ViewerPageState,
    gallery: ViewerVerticalGalleryState,
    isCurrentPost: Boolean,
    screen: ViewerScreenState,
    host: ViewerScreenHost,
) {
    val list = gallery.list
    val pageCount = pageState.media.size
    val selected = pageState.selectedMediaIndex
    val resuming = pageState.pendingMediaIndex != null
    // The list's position counts as the reader's choice only once it shows the selection; until then (a resume
    // still resolving, or a restored page not yet scrolled to) publishing it would overwrite the saved page.
    var aligned by remember(list) { mutableStateOf(false) }
    VerticalGalleryScrollSelection(list, pageCount, selected, enabled = isCurrentPost && aligned, host)
    // Keyed by the post too, so a chapter whose pages arrive while it shows is reported again, now with its pages.
    LaunchedEffect(post, selected, isCurrentPost, aligned) {
        if (isCurrentPost && aligned) host.onVisibleMediaChanged?.invoke(post, selected + 1)
    }
    // A page bar, overview or strip pick, or a resumed page, moves the list to the selection.
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

/**
 * Selects the page under the line as the reader scrolls, including the one a fling comes to rest on. Pages also slide
 * under the line when loading pages resize or the Viewer moves the list itself; selecting those would answer the jump
 * that moved them, and the two would trade pages back and forth.
 */
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

/** A swipe that lands on this gallery at a chosen page (going back lands on its last page) scrolls there once. */
@Composable
private fun VerticalGalleryPendingJump(post: Post, list: LazyListState, pageCount: Int, resuming: Boolean, screen: ViewerScreenState) {
    val pendingJump = screen.pendingMediaJumpByPost[post.id]
    LaunchedEffect(pendingJump, pageCount, resuming) {
        if (pendingJump == null || resuming || pageCount == 0) return@LaunchedEffect
        list.scrollToSelection(pendingJump.coerceIn(0, pageCount - 1))
        screen.pendingMediaJumpByPost.remove(post.id)
    }
}

/**
 * The page under the selection line (a third down the screen); at either end of the list, the first or last page,
 * so pages shorter than that third can still be selected there.
 */
private fun LazyListState.focusedPage(pageCount: Int): Int {
    if (pageCount <= 0) return 0
    val info = layoutInfo
    // Before its first layout the list cannot scroll either way, which must not read as the last page.
    if (info.totalItemsCount == 0) return firstVisibleItemIndex.coerceIn(0, pageCount - 1)
    if (!canScrollBackward) return 0
    if (!canScrollForward) return pageCount - 1
    val line = selectionLine()
    val crossing = info.visibleItemsInfo.firstOrNull { it.offset <= line && it.offset + it.size > line }?.index
    return (crossing ?: firstVisibleItemIndex).coerceIn(0, pageCount - 1)
}

private fun LazyListState.selectionLine(): Int = with(layoutInfo) { viewportStartOffset + (viewportEndOffset - viewportStartOffset) / 3 }

/**
 * Scrolls [index] to the top, then, if it ends above the selection line, down until it crosses it. Both moves are
 * instant, so composition never sees them as a scroll in progress, which would count them as the reader's.
 */
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
    /** The Viewer's error for this page while it is selected, such as a failed gallery lookup, retried through [onRetry]. */
    sharedError: ViewerMediaError?,
    onRetry: () -> Unit,
    onPainted: (Float, Float) -> Unit,
    onRequestMediaRecovery: ViewerMediaRecoveryRequest?,
) {
    val media = pageState.media[index]
    val key = ViewerMediaKey(post.id, index)
    val generation = media.loadGeneration
    // Resolution can swap a cover for the full page under the same key and generation, so the image keys these too.
    var painted by remember(key, generation, media.ref) { mutableStateOf(false) }
    var failed by remember(key, generation, media.ref) { mutableStateOf<String?>(null) }
    var attempt by remember(key, generation, media.ref) { mutableIntStateOf(0) }
    // Readiness and failure are reported for the selected page only, and again when a settled page becomes selected.
    val events = viewerRendererEvents(
        session = null, key = key, generation = generation, onAction = {}, onTimelineInteractionActiveChanged = {}, onDurationKnown = {},
    ).copy(onReady = { painted = true; failed = null }, onError = { failed = it })
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
            .semantics { contentDescription = "Page ${index + 1}" },
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(shape), contentAlignment = Alignment.Center) {
            key(attempt) {
                ViewerImageRenderer(
                    post = post, media = media.ref, key = key, animated = false,
                    modifier = Modifier.fillMaxSize(), mediaModifier = Modifier,
                    controls = VERTICAL_PAGE_CONTROLS.copy(loadGeneration = generation),
                    events = events, imageSession = imageSession,
                    onRequestMediaRecovery = onRequestMediaRecovery,
                    onPainted = { size -> onPainted(size.width, size.height) },
                    spinnerModifier = Modifier.onVisiblePart(gallery.list, index),
                    // Laid out in a borrowed shape until it paints; sizing the decode from that would blur a tall strip.
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

/**
 * Moves a status centered on page [index] of [list] (its spinner or error) to the middle of the part of that page on
 * screen, so a page taller than the screen never looks blank while it loads. Read while drawing, so scrolling moves it
 * without recomposing.
 */
private fun Modifier.onVisiblePart(list: LazyListState, index: Int) = graphicsLayer {
    val viewport = list.layoutInfo
    val page = viewport.visibleItemsInfo.firstOrNull { it.index == index } ?: return@graphicsLayer
    translationY = visiblePartShift(
        page.offset, page.size, viewport.viewportStartOffset, viewport.viewportEndOffset,
        reach = size.height / 2 + STATUS_EDGE_MARGIN.toPx(),
    )
}

/**
 * How far to move a status from the middle of a page at [pageOffset] and [pageSize] in a viewport from [viewportStart]
 * to [viewportEnd] so it sits in the middle of the page's part on screen: it rises with a page coming in at the bottom,
 * holds the screen's middle while the page fills it, and leaves with the page at the top. It stays [reach] inside the
 * page's edges, and does not move on a page too short for that.
 */
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

/**
 * Pinch zooms the column around the fingers and, while zoomed, a sideways drag pans it. Both read the Initial pass so
 * the list never scrolls under a pinch; vertical drags stay with the list, at the same speed as the finger.
 */
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

/** Tap toggles the chrome, double tap zooms in to twice the width or back out, and a long press opens info. */
private fun Modifier.verticalGalleryTaps(
    gallery: ViewerVerticalGalleryState,
    controls: ViewerControls,
    scope: CoroutineScope,
    onAction: (ViewerAction) -> Unit,
) = pointerInput(gallery) {
    detectTapGestures(
        onTap = { controls.toggleChrome() },
        onDoubleTap = { tap ->
            scope.launch { gallery.animateZoomToggle(tap) }
            controls.markPassiveInteraction()
        },
        onLongPress = {
            onAction(ViewerAction.ShowPanel(ViewerPanel.INFO))
            controls.markInteraction()
        },
    )
}

/** Zooms to twice the width around [tap], or back to fit. */
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
private const val MAX_ZOOM = 4f
private const val DOUBLE_TAP_ZOOM = 2f

/** How close a page's spinner or error comes to the page's top and bottom edges. */
private val STATUS_EDGE_MARGIN = 24.dp
