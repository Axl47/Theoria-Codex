package com.theoriacodex.app.viewer

import com.theoriacodex.app.viewer.state.ViewerMediaKind
import com.theoriacodex.app.viewer.state.ViewerPageState
import com.theoriacodex.app.viewer.state.ViewerResolutionStatus
import com.theoriacodex.domain.model.GalleryReadingMode

internal fun ViewerScreenConfig.readingModeOf(page: ViewerPageState?, series: ViewerSeries?): GalleryReadingMode {
    if (page == null || (page.swipeMediaCount() <= 1 && series !is ViewerSeries.Reading)) return GalleryReadingMode.LEFT_TO_RIGHT
    val chosen = galleryReadingModes[page.post.id]?.takeIf { it != GalleryReadingMode.VERTICAL || page.verticalReadingAvailable() }
    return chosen ?: if (invertMultiImageScrollDirection) GalleryReadingMode.RIGHT_TO_LEFT else GalleryReadingMode.LEFT_TO_RIGHT
}

internal fun ViewerScreenConfig.entryMediaIndex(page: ViewerPageState, series: ViewerSeries?, back: Boolean = false): Int {
    val finished = series is ViewerSeries.Reading && page.pendingMediaIndex == null && page.selectedMediaIndex > 0 && page.selectedMediaIndex >= page.media.lastIndex
    return if (finished && !back) 0 else page.targetMediaIndex
}

internal fun ViewerPageState.verticalReadingAvailable(): Boolean = media.all { it.kind == ViewerMediaKind.IMAGE }

internal fun ViewerPageState.awaitsChapterPages(series: ViewerSeries?): Boolean =
    series is ViewerSeries.Reading && resolution.status in CHAPTER_PAGES_PENDING

private val CHAPTER_PAGES_PENDING = setOf(ViewerResolutionStatus.IDLE, ViewerResolutionStatus.REQUESTED, ViewerResolutionStatus.RESOLVING)
