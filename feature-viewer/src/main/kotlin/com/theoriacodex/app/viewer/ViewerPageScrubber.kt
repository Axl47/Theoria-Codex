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
import coil.memory.MemoryCache
import com.theoriacodex.app.ui.motion.TheoriaMotion
import com.theoriacodex.domain.model.SourceKey
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

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
    val holdPx = with(LocalDensity.current) { PAGE_HOLD.toPx() }

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
                    val touched = viewerPageAt(x, scrub.widthPx, pageCount, mirrored)
                    scrub.begin(latestPage)
                    scrub.moveTo(touched, instantSeek)
                },
                onMove = { x -> scrub.moveTo(viewerPageAt(x, scrub.widthPx, pageCount, mirrored, scrub.target, holdPx), instantSeek) },
                onEnd = { released -> scrub.end(released, instantSeek) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        ViewerPageTrack(
            shownPage, pageCount, scrub.active,
            Modifier.fillMaxWidth().height(VIEWER_TIMELINE_TRACK_HEIGHT).graphicsLayer { if (mirrored) scaleX = -1f },
        )
        val target = scrub.target
        if (target != null) {
            if (instantSeek) ViewerPageReadout(target, scrub.startPage, pageCount, mirrored) else ViewerPagePreview(source, items, target, scrub.startPage, aspectRatio, mirrored)
        }
    }
}

internal fun viewerPageAt(x: Float, widthPx: Float, pageCount: Int, mirrored: Boolean, held: Int? = null, holdPx: Float = 0f): Int {
    if (held != null) {
        val slot = widthPx / pageCount
        val start = (if (mirrored) pageCount - 1 - held else held) * slot
        val hold = minOf(holdPx, slot / 2)
        if (x >= start - hold && x < start + slot + hold) return held
    }
    val page = ((x / widthPx).coerceIn(0f, 1f) * pageCount).toInt().coerceAtMost(pageCount - 1)
    return if (mirrored) pageCount - 1 - page else page
}

private class PageScrubCallbacks(val onPageSelected: (Int) -> Unit, val onActiveChanged: (Boolean) -> Unit)

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

    fun end(released: Boolean, instantSeek: Boolean) {
        val landed = target
        if (landed != null) {
            target = null
            if (released && !instantSeek && landed != startPage) callbacks.value.onPageSelected(landed)
            callbacks.value.onActiveChanged(false)
        }
    }

    fun abandon() = end(released = false, instantSeek = false)
}

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

@Composable
private fun ViewerPageReadout(target: Int, startPage: Int, pageCount: Int, mirrored: Boolean) {
    val centre = (target + 0.5f) / pageCount
    ViewerScrubPopup(viewerPageReadout(target, pageCount), viewerPageDelta(target, startPage), if (mirrored) 1f - centre else centre) {}
}

private fun viewerPageReadout(target: Int, pageCount: Int) = "${target + 1} / $pageCount"

private fun viewerPageDelta(target: Int, startPage: Int): String {
    val delta = target - startPage
    return "${if (delta < 0) "-" else "+"}${abs(delta)}"
}

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
    var sharpPage by remember { mutableIntStateOf(-1) }
    LaunchedEffect(settled) {
        delay(VIEWER_SHARP_SETTLE_MS)
        sharpPage = settled
    }
    var lastCentre by remember { mutableStateOf<MemoryCache.Key?>(null) }
    val frame = viewerPagePreviewFrame(aspectRatio, LocalConfiguration.current.screenWidthDp.dp - 16.dp)
    val sideFrame = frame * SIDE_FRAME_SCALE
    val decodeEdgePx = with(LocalDensity.current) { maxOf(frame.width, frame.height).roundToPx() }
    val step = if (mirrored) -1 else 1
    val centre = (target + 0.5f) / items.size
    ViewerScrubPopup(viewerPageReadout(target, items.size), viewerPageDelta(target, startPage), if (mirrored) 1f - centre else centre) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PREVIEW_FRAME_GAP)) {
            for (slot in -1..1) {
                val item = items.getOrNull(settled + slot * step)
                key(item?.mediaIndex ?: (-1 - slot)) {
                    val look = if (slot == 0) {
                        PreviewFrameLook(frame, ContentScale.Fit, 1f, CentreFrame(sharpPage == settled, lastCentre) { lastCentre = it })
                    } else {
                        PreviewFrameLook(sideFrame, ContentScale.Crop, SIDE_FRAME_ALPHA, centre = null)
                    }
                    ViewerPagePreviewFrame(source, item, look, decodeEdgePx)
                }
            }
        }
    }
}

private class PreviewFrameLook(val size: DpSize, val scale: ContentScale, val opacity: Float, val centre: CentreFrame?)

private class CentreFrame(val sharp: Boolean, val placeholder: MemoryCache.Key?, val onLoaded: (MemoryCache.Key?) -> Unit)

@Composable
private fun ViewerPagePreviewFrame(source: SourceKey, item: ViewerMediaOverviewItem?, look: PreviewFrameLook, decodeEdgePx: Int) {
    if (item == null) {
        Spacer(Modifier.size(look.size))
        return
    }
    val centre = look.centre
    Box(Modifier.alpha(look.opacity).viewerScrubFrame(look.size)) {
        val thumbnail = rememberViewerThumbnailRequest(source, item, decodeEdgePx)
        val request = remember(thumbnail) { thumbnail?.newBuilder()?.placeholderMemoryCacheKey(centre?.placeholder)?.build() }
        request?.let {
            AsyncImage(
                it, null, Modifier.fillMaxSize(), contentScale = look.scale,
                onSuccess = centre?.let { c -> { success -> c.onLoaded(success.result.memoryCacheKey) } },
            )
        }
        if (centre?.sharp == true && item.posterLocation != item.thumbnailLocation) {
            rememberViewerPosterRequest(source, item)?.let { AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = look.scale) }
        }
    }
}

internal fun viewerPagePreviewFrame(aspectRatio: Float?, availableWidth: Dp): DpSize {
    val frame = viewerScrubFrameSize(aspectRatio)
    val framesWidth = availableWidth - PREVIEW_FRAME_GAP * 2
    return frame * (framesWidth / (frame.width * (1f + 2f * SIDE_FRAME_SCALE))).coerceAtMost(1f)
}

private const val MAX_PAGE_SEGMENTS = 24
private val PAGE_HOLD = 6.dp
private const val SIDE_FRAME_SCALE = 0.55f
private const val SIDE_FRAME_ALPHA = 0.5f
private val PREVIEW_FRAME_GAP = 6.dp
