package com.theoriacodex.domain.model

/** Completion behavior shared by all Viewer videos and animations. */
enum class ViewerPlaybackMode(val label: String) {
    LOOP("Loop"),
    ONCE("Play once"),
    NEXT("Next in queue"),
}
