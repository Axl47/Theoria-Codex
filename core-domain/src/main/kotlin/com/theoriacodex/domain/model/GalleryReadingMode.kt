package com.theoriacodex.domain.model

/** How the Viewer lays out a multi-page post's pages; chosen per gallery and remembered. */
enum class GalleryReadingMode {
    LEFT_TO_RIGHT,
    RIGHT_TO_LEFT,

    /** One continuous top-to-bottom column; horizontal swipes then move between posts. */
    VERTICAL,
    ;

    /** Whether the horizontal page pager runs mirrored. */
    val reversed: Boolean get() = this == RIGHT_TO_LEFT
}
