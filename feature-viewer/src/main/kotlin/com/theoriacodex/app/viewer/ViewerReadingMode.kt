package com.theoriacodex.app.viewer

import com.theoriacodex.app.viewer.state.ViewerMediaKind
import com.theoriacodex.app.viewer.state.ViewerPageState
import com.theoriacodex.domain.model.GalleryReadingMode

/**
 * How [page] lays out its pages: the mode chosen for its gallery, else the global direction. A single page always
 * runs left to right, except a series' one-page chapter, which keeps the series' mode so the gesture to the next
 * chapter stays the one the reader has been making. A saved Vertical gives way while the gallery holds pages that are
 * not still images.
 */
internal fun ViewerScreenConfig.readingModeOf(page: ViewerPageState?): GalleryReadingMode {
    if (page == null || (page.swipeMediaCount() <= 1 && !seriesChapters)) return GalleryReadingMode.LEFT_TO_RIGHT
    val chosen = galleryReadingModes[page.post.id]?.takeIf { it != GalleryReadingMode.VERTICAL || page.verticalReadingAvailable() }
    return chosen ?: if (invertMultiImageScrollDirection) GalleryReadingMode.RIGHT_TO_LEFT else GalleryReadingMode.LEFT_TO_RIGHT
}

/** Vertical reading is for still-image galleries: video and animation need the pager's one active page to play. */
internal fun ViewerPageState.verticalReadingAvailable(): Boolean = media.all { it.kind == ViewerMediaKind.IMAGE }
