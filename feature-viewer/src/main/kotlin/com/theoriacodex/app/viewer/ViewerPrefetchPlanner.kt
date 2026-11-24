package com.theoriacodex.app.viewer

import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.app.viewer.state.ViewerMediaKey
import com.theoriacodex.app.viewer.state.ViewerMediaKind
import com.theoriacodex.app.viewer.state.ViewerPageState
import com.theoriacodex.app.viewer.state.ViewerPrefetchRequest
import kotlin.math.abs

internal const val VIEWER_PREFETCH_LEFT_COUNT = 3
internal const val VIEWER_PREFETCH_RIGHT_COUNT = 3

internal const val VIEWER_VERTICAL_PREFETCH_RIGHT_COUNT = 6

internal const val VIEWER_VIDEO_PREFETCH_DISTANCE = 1

internal const val VIEWER_MEMORY_PREFETCH_DISTANCE = 1

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

fun viewerPrefetchAction(
    pages: List<ViewerPageState>,
    current: ViewerMediaKey?,
    swipeTarget: ViewerMediaKey?,
    verticalPageWidthPx: (ViewerPageState) -> Int? = { null },
): ViewerAction.QueuePrefetch {
    val flattened = pages.flatMap(ViewerPageState::media)
    val currentIndex = current?.let { key -> flattened.indexOfFirst { media -> media.key == key } } ?: -1
    fun pageWidthPx(key: ViewerMediaKey) = pages.firstOrNull { page -> page.post.id == key.postId }?.let(verticalPageWidthPx)
    val vertical = current?.let(::pageWidthPx) != null
    val rightCount = if (vertical) VIEWER_VERTICAL_PREFETCH_RIGHT_COUNT else VIEWER_PREFETCH_RIGHT_COUNT
    val keys = (listOfNotNull(swipeTarget) + planAdjacentViewerPrefetch(pages, current, rightCount = rightCount)).distinct()
    return ViewerAction.QueuePrefetch(
        keys.map { key ->
            val index = flattened.indexOfFirst { media -> media.key == key }
            val distance = if (currentIndex < 0 || index < 0) Int.MAX_VALUE else abs(index - currentIndex)
            val image = flattened.getOrNull(index)?.kind == ViewerMediaKind.IMAGE
            ViewerPrefetchRequest(key, distance, decode = image && distance <= VIEWER_MEMORY_PREFETCH_DISTANCE, pageWidthPx = pageWidthPx(key))
        },
    )
}
