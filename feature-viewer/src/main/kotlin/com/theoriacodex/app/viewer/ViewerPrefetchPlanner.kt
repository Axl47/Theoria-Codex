package com.theoriacodex.app.viewer

import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.app.viewer.state.ViewerMediaKey
import com.theoriacodex.app.viewer.state.ViewerMediaKind
import com.theoriacodex.app.viewer.state.ViewerPageState
import com.theoriacodex.app.viewer.state.ViewerPrefetchRequest
import kotlin.math.abs

internal const val VIEWER_PREFETCH_LEFT_COUNT = 3
internal const val VIEWER_PREFETCH_RIGHT_COUNT = 3

/** Vertical reading only moves forward through a column of pages it shows several at a time, so it looks further ahead. */
internal const val VIEWER_VERTICAL_PREFETCH_RIGHT_COUNT = 6

/** Videos and ugoira archives warm only the immediate neighbours; each one can cost megabytes of disk cache. */
internal const val VIEWER_VIDEO_PREFETCH_DISTANCE = 1

/**
 * Prefetched images this close to the current media stay decoded in Coil's memory cache. Farther ones
 * warm the disk cache only, so a full prefetch window cannot evict the feed's grid thumbnails.
 */
internal const val VIEWER_MEMORY_PREFETCH_DISTANCE = 1

/**
 * Orders speculative Viewer work: nearest right neighbours first, then nearest left ones. Images use the
 * full window; videos and ugoira beyond [VIEWER_VIDEO_PREFETCH_DISTANCE] are left to load when they are shown.
 */
internal fun planAdjacentViewerPrefetch(
    pages: List<ViewerPageState>,
    current: ViewerMediaKey?,
    leftCount: Int = VIEWER_PREFETCH_LEFT_COUNT,
    rightCount: Int = VIEWER_PREFETCH_RIGHT_COUNT,
): List<ViewerMediaKey> {
    if (current == null || leftCount < 0 || rightCount < 0) return emptyList()
    val flattened = pages.flatMap(ViewerPageState::media)
    val currentIndex = flattened.indexOfFirst { media -> media.key == current }
    if (currentIndex < 0) return emptyList()

    fun neighbour(offset: Int) = flattened.getOrNull(currentIndex + offset)?.takeUnless { media ->
        (media.kind == ViewerMediaKind.VIDEO || media.kind == ViewerMediaKind.UGOIRA) &&
            abs(offset) > VIEWER_VIDEO_PREFETCH_DISTANCE
    }?.key

    return buildList {
        for (offset in 1..rightCount) neighbour(offset)?.let(::add)
        for (offset in 1..leftCount) neighbour(-offset)?.let(::add)
    }.distinct().filterNot { key -> key == current }
}

/**
 * What the Viewer asks its owner to prefetch: a swipe's target first, then the adjacent plan. While reading
 * vertically, [verticalPageWidthPx] is the width pages lay out at: the plan looks further ahead, and images decode
 * as full-width pages of that width. Static images within [VIEWER_MEMORY_PREFETCH_DISTANCE] are marked for decoding, so
 * a page first downloaded while it was far away is decoded into memory once it becomes a neighbour, and shows
 * without a decode when reached.
 */
fun viewerPrefetchAction(
    pages: List<ViewerPageState>,
    current: ViewerMediaKey?,
    swipeTarget: ViewerMediaKey?,
    verticalPageWidthPx: Int? = null,
): ViewerAction.QueuePrefetch {
    val flattened = pages.flatMap(ViewerPageState::media)
    val currentIndex = current?.let { key -> flattened.indexOfFirst { media -> media.key == key } } ?: -1
    val rightCount = if (verticalPageWidthPx != null) VIEWER_VERTICAL_PREFETCH_RIGHT_COUNT else VIEWER_PREFETCH_RIGHT_COUNT
    val keys = (listOfNotNull(swipeTarget) + planAdjacentViewerPrefetch(pages, current, rightCount = rightCount)).distinct()
    return ViewerAction.QueuePrefetch(
        keys.map { key ->
            val index = flattened.indexOfFirst { media -> media.key == key }
            // Media-item distance in the flattened stream; unknown when either end is absent.
            val distance = if (currentIndex < 0 || index < 0) Int.MAX_VALUE else abs(index - currentIndex)
            val image = flattened.getOrNull(index)?.kind == ViewerMediaKind.IMAGE
            ViewerPrefetchRequest(key, distance, decode = image && distance <= VIEWER_MEMORY_PREFETCH_DISTANCE, pageWidthPx = verticalPageWidthPx)
        },
    )
}
