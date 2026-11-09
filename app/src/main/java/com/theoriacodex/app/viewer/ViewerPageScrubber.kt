package com.theoriacodex.app.viewer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import coil.compose.AsyncImage
import com.theoriacodex.app.ui.motion.TheoriaMotion
import com.theoriacodex.domain.model.SourceKey
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * Seeks a multi-page post in whole-page steps, the way the video timeline seeks time. A drag floats the target
 * page and its neighbours and turns the page once, on release; with instant seek each step turns the page instead.
 * [mirrored] matches a right-to-left media pager, so dragging follows the direction pages swipe.
 */
@Composable
internal fun ViewerPageScrubber(
    source: SourceKey,
    items: List<ViewerMediaOverviewItem>,
    aspectRatio: Float?,
    pageIndex: Int,
    mirrored: Boolean,
    onPageSelected: (Int) -> Unit,
    onInteractionActiveChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val instantSeek = LocalViewerInstantSeek.current
    val pageCount = items.size
    val latestPage by rememberUpdatedState(pageIndex)
    val callbacks = rememberUpdatedState(PageScrubCallbacks(onPageSelected, onInteractionActiveChanged))
    val scrub = remember { PageScrubState(callbacks) }
    DisposableEffect(Unit) { onDispose(scrub::abandon) }
    val shownPage = scrub.target ?: pageIndex

    Box(
        modifier.fillMaxWidth().padding(horizontal = 16.dp).height(VIEWER_TIMELINE_TRACK_HEIGHT)
            .onSizeChanged { scrub.widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .testTag("viewer-page-scrubber")
            .clearAndSetSemantics {
                contentDescription = "Page ${shownPage + 1} of $pageCount"
                progressBarRangeInfo = ProgressBarRangeInfo(shownPage.toFloat(), 0f..(pageCount - 1).toFloat(), (pageCount - 2).coerceAtLeast(0))
                setProgress { value ->
                    onPageSelected(value.roundToInt().coerceIn(0, pageCount - 1))
                    true
                }
            }
            .timelineScrubGestures(
                pageCount, mirrored,
                onBegin = { x ->
                    scrub.begin(latestPage)
                    scrub.moveTo(viewerPageAt(x, scrub.widthPx, pageCount, mirrored), instantSeek)
                },
                onMove = { x -> scrub.moveTo(viewerPageAt(x, scrub.widthPx, pageCount, mirrored), instantSeek) },
                onEnd = { released -> scrub.end(released, instantSeek) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        ViewerPageTrack(
            shownPage, pageCount, scrub.active,
            Modifier.fillMaxWidth().height(VIEWER_TIMELINE_TRACK_HEIGHT).graphicsLayer { if (mirrored) scaleX = -1f },
        )
        val target = scrub.target
        if (target != null && !instantSeek) ViewerPagePreview(source, items, target, scrub.startPage, aspectRatio, mirrored)
    }
}

/** The page a touch at [x] on a track [widthPx] wide stands for: each page owns an equal share of the track. */
internal fun viewerPageAt(x: Float, widthPx: Float, pageCount: Int, mirrored: Boolean): Int {
    val page = ((x / widthPx).coerceIn(0f, 1f) * pageCount).toInt().coerceAtMost(pageCount - 1)
    return if (mirrored) pageCount - 1 - page else page
}

/** What the scrubber tells the screen about a drag. */
private class PageScrubCallbacks(val onPageSelected: (Int) -> Unit, val onActiveChanged: (Boolean) -> Unit)

/** A drag in progress: the page it rests on, the page it began from, and the track width that maps touches to pages. */
private class PageScrubState(private val callbacks: State<PageScrubCallbacks>) {
    var target by mutableStateOf<Int?>(null)
        private set
    var startPage by mutableIntStateOf(0)
        private set
    var widthPx = 1f
    val active get() = target != null

    fun begin(fromPage: Int) {
        startPage = fromPage
        callbacks.value.onActiveChanged(true)
    }

    fun moveTo(page: Int, instantSeek: Boolean) {
        val moved = page != (target ?: startPage)
        target = page
        if (instantSeek && moved) callbacks.value.onPageSelected(page)
    }

    /** A released drag turns to its page; anything else leaves the post where it was. */
    fun end(released: Boolean, instantSeek: Boolean) {
        val landed = target
        if (landed != null) {
            target = null
            if (released && !instantSeek && landed != startPage) callbacks.value.onPageSelected(landed)
            callbacks.value.onActiveChanged(false)
        }
    }

    /** The scrubber left composition, so a held drag cannot complete. */
    fun abandon() = end(released = false, instantSeek = false)
}

/** Filled through [page] with one segment per page. Long galleries become one continuous bar, since segments would vanish. */
@Composable
private fun ViewerPageTrack(page: Int, pageCount: Int, emphasized: Boolean, modifier: Modifier) {
    if (pageCount > MAX_PAGE_SEGMENTS) {
        ViewerTimelineTrack({ (page + 1f) / pageCount }, modifier, emphasized)
        return
    }
    val accent = LocalViewerAccent.current
    val emphasis by animateFloatAsState(if (emphasized) 1f else 0f, tween(TheoriaMotion.SHORT_MS), label = "Viewer page track emphasis")
    Canvas(modifier) {
        val gap = 4.dp.toPx()
        val segment = (size.width - gap * (pageCount - 1)) / pageCount
        val thickness = lerp(4.dp.toPx(), 10.dp.toPx(), emphasis)
        repeat(pageCount) { index ->
            val left = index * (segment + gap)
            drawPill(if (index <= page) accent else ViewerVeil.InactiveTrack, left, center.y, left + segment, thickness)
        }
    }
}

/** The floating target page between its neighbours. The readout follows the finger; images wait for it to rest, sparing providers a fast drag. */
@Composable
private fun ViewerPagePreview(
    source: SourceKey,
    items: List<ViewerMediaOverviewItem>,
    target: Int,
    startPage: Int,
    aspectRatio: Float?,
    mirrored: Boolean,
) {
    var settled by remember { mutableIntStateOf(target) }
    LaunchedEffect(target) {
        delay(SCRUB_FRAME_SETTLE_MS)
        settled = target
    }
    // The original is heavy and waits for the finger to stay put longer than the thumbnails do. Compared with
    // [settled] rather than reset, so the sharp page never flashes back to the thumbnail between pages.
    var sharpPage by remember { mutableIntStateOf(-1) }
    LaunchedEffect(settled) {
        delay(SCRUB_SHARP_SETTLE_MS)
        sharpPage = settled
    }
    val frame = viewerPagePreviewFrame(aspectRatio, LocalConfiguration.current.screenWidthDp.dp - 16.dp)
    val sideFrame = frame * SIDE_FRAME_SCALE
    val decodeEdgePx = with(LocalDensity.current) { maxOf(frame.width, frame.height).roundToPx() }
    val step = if (mirrored) -1 else 1
    val delta = target - startPage
    val centre = (target + 0.5f) / items.size
    ViewerScrubPopup("${target + 1} / ${items.size}", "${if (delta < 0) "-" else "+"}${abs(delta)}", if (mirrored) 1f - centre else centre) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PREVIEW_FRAME_GAP)) {
            for (slot in -1..1) {
                val item = items.getOrNull(settled + slot * step)
                // Keyed by page, not position, so a frame sliding from the side to the centre keeps its load.
                key(item?.mediaIndex ?: (-1 - slot)) {
                    val isCentre = slot == 0
                    ViewerPagePreviewFrame(
                        source, item,
                        if (isCentre) frame else sideFrame,
                        if (isCentre) ContentScale.Fit else ContentScale.Crop,
                        if (isCentre) 1f else SIDE_FRAME_ALPHA,
                        decodeEdgePx,
                        sharp = isCentre && sharpPage == settled,
                    )
                }
            }
        }
    }
}

/**
 * A past-the-end neighbour still holds its space, so the target page stays centred over the finger. Every frame
 * decodes at [decodeEdgePx], so a frame keeps the same request when it moves from one slot to another.
 */
@Composable
private fun ViewerPagePreviewFrame(
    source: SourceKey,
    item: ViewerMediaOverviewItem?,
    frameSize: DpSize,
    scale: ContentScale,
    opacity: Float,
    decodeEdgePx: Int,
    sharp: Boolean,
) {
    if (item == null) {
        Spacer(Modifier.size(frameSize))
        return
    }
    Box(Modifier.alpha(opacity).viewerScrubFrame(frameSize)) {
        rememberViewerThumbnailRequest(source, item, decodeEdgePx)?.let { AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = scale) }
        // The original goes over the thumbnail, which stays underneath until it has loaded.
        if (sharp && item.posterLocation != item.thumbnailLocation) {
            rememberViewerPosterRequest(source, item)?.let { AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = scale) }
        }
    }
}

/** The target frame in the media's shape, shrunk only as far as it takes for it and both neighbours to fit [availableWidth]. */
internal fun viewerPagePreviewFrame(aspectRatio: Float?, availableWidth: Dp): DpSize {
    val frame = viewerScrubFrameSize(aspectRatio)
    val framesWidth = availableWidth - PREVIEW_FRAME_GAP * 2
    return frame * (framesWidth / (frame.width * (1f + 2f * SIDE_FRAME_SCALE))).coerceAtMost(1f)
}

private const val MAX_PAGE_SEGMENTS = 24
private const val SCRUB_SHARP_SETTLE_MS = 250L
private const val SIDE_FRAME_SCALE = 0.55f
private const val SIDE_FRAME_ALPHA = 0.5f
private val PREVIEW_FRAME_GAP = 6.dp
