package com.theoriacodex.domain.model

enum class ViewerOrientation {
    PORTRAIT, LANDSCAPE;

    val opposite: ViewerOrientation get() = if (this == PORTRAIT) LANDSCAPE else PORTRAIT
}
