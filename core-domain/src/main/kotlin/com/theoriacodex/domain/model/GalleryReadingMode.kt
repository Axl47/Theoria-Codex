package com.theoriacodex.domain.model

enum class GalleryReadingMode {
    LEFT_TO_RIGHT,
    RIGHT_TO_LEFT,

    VERTICAL,
    ;

    val reversed: Boolean get() = this == RIGHT_TO_LEFT
}
