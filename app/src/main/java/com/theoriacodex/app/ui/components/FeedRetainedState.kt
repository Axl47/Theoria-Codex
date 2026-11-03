package com.theoriacodex.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.theoriacodex.app.media.DurationFilterProjection

/**
 * Navigation-entry feed state that must outlive composition. The Viewer is its own destination,
 * so a feed leaves composition each time a post opens, and plain `remember` would forget this.
 */
internal class FeedRetainedState : ViewModel() {
    private val durationProjections = mutableMapOf<String, DurationFilterProjection>()

    fun durationProjection(key: String): DurationFilterProjection =
        durationProjections.getOrPut(key) { DurationFilterProjection() }
}

/** The reveal order of a feed survives the Viewer, so a late post stays where it landed. */
@Composable
internal fun rememberRetainedDurationProjection(key: String): DurationFilterProjection {
    val retained = viewModel<FeedRetainedState>()
    return remember(retained, key) { retained.durationProjection(key) }
}
