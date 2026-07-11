package com.theoriacodex.app.search

import com.theoriacodex.app.media.AnimatedDurationRange

/** Platform-free visibility context carried from Search rendering into Viewer navigation. */
data class SearchVisibilityFilters(
    val animatedOnly: Boolean = false,
    val hideLiked: Boolean = false,
    val hideSaved: Boolean = false,
    val animatedDurationRange: AnimatedDurationRange = AnimatedDurationRange.Full,
)
