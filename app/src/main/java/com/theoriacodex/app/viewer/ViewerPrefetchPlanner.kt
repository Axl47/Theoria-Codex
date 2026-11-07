package com.theoriacodex.app.viewer

import com.theoriacodex.app.viewer.state.ViewerMediaKey
import com.theoriacodex.app.viewer.state.ViewerMediaKind
import com.theoriacodex.app.viewer.state.ViewerPageState
import kotlin.math.abs

internal const val VIEWER_PREFETCH_LEFT_COUNT = 3
internal const val VIEWER_PREFETCH_RIGHT_COUNT = 3

/** Videos warm only the immediate neighbours; each one can cost megabytes of the shared video cache. */
internal const val VIEWER_VIDEO_PREFETCH_DISTANCE = 1

/**
 * Prefetched images this close to the current media stay decoded in Coil's memory cache. Farther ones
 * warm the disk cache only, so a full prefetch window cannot evict the feed's grid thumbnails.
 */
internal const val VIEWER_MEMORY_PREFETCH_DISTANCE = 1

/**
 * Orders speculative Viewer work: nearest right neighbours first, then nearest left ones. Images use the
 * full window; videos beyond [VIEWER_VIDEO_PREFETCH_DISTANCE] are left to load when they are shown.
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
        media.kind == ViewerMediaKind.VIDEO && abs(offset) > VIEWER_VIDEO_PREFETCH_DISTANCE
    }?.key

    return buildList {
        for (offset in 1..rightCount) neighbour(offset)?.let(::add)
        for (offset in 1..leftCount) neighbour(-offset)?.let(::add)
    }.distinct().filterNot { key -> key == current }
}

/** What the Viewer asks its owner to prefetch: a swipe's target first, then the adjacent plan. */
internal fun viewerPrefetchQueue(
    pages: List<ViewerPageState>,
    current: ViewerMediaKey?,
    swipeTarget: ViewerMediaKey?,
): List<ViewerMediaKey> = (listOfNotNull(swipeTarget) + planAdjacentViewerPrefetch(pages, current)).distinct()

/** Media-item distance between [current] and [target] in the flattened Viewer stream; null when either is absent. */
internal fun viewerPrefetchDistance(
    pages: List<ViewerPageState>,
    current: ViewerMediaKey?,
    target: ViewerMediaKey,
): Int? {
    if (current == null) return null
    val flattened = pages.flatMap(ViewerPageState::media)
    val currentIndex = flattened.indexOfFirst { media -> media.key == current }
    val targetIndex = flattened.indexOfFirst { media -> media.key == target }
    if (currentIndex < 0 || targetIndex < 0) return null
    return abs(targetIndex - currentIndex)
}
