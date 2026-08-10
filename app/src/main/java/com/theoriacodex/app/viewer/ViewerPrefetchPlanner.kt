package com.theoriacodex.app.viewer

import com.theoriacodex.app.viewer.state.ViewerMediaKey
import com.theoriacodex.app.viewer.state.ViewerPageState

internal const val VIEWER_PREFETCH_LEFT_COUNT = 3
internal const val VIEWER_PREFETCH_RIGHT_COUNT = 3

internal fun planAdjacentViewerPrefetch(
    pages: List<ViewerPageState>,
    current: ViewerMediaKey?,
    leftCount: Int = VIEWER_PREFETCH_LEFT_COUNT,
    rightCount: Int = VIEWER_PREFETCH_RIGHT_COUNT,
): List<ViewerMediaKey> {
    if (current == null || leftCount < 0 || rightCount < 0) return emptyList()
    val flattened = pages.flatMap { page -> page.media.map { media -> media.key } }
    val currentIndex = flattened.indexOf(current)
    if (currentIndex < 0) return emptyList()

    return buildList {
        for (offset in 1..rightCount) {
            flattened.getOrNull(currentIndex + offset)?.let(::add)
        }
        for (offset in 1..leftCount) {
            flattened.getOrNull(currentIndex - offset)?.let(::add)
        }
    }.distinct().filterNot { key -> key == current }
}
