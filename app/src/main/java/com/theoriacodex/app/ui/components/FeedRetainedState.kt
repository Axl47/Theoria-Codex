package com.theoriacodex.app.ui.components

import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.theoriacodex.app.media.DurationFilterProjection

/**
 * Navigation-entry feed state that must outlive composition. The Viewer is its own destination,
 * so a feed leaves composition each time a post opens, and plain `remember` would forget this.
 */
internal class FeedRetainedState : ViewModel() {
    private val durationProjections = mutableMapOf<String, DurationFilterProjection>()
    private val gridStates = mutableMapOf<String, LazyStaggeredGridState>()

    fun durationProjection(key: String): DurationFilterProjection =
        durationProjections.getOrPut(key) { DurationFilterProjection() }

    fun gridState(key: String, create: () -> LazyStaggeredGridState): LazyStaggeredGridState =
        gridStates.getOrPut(key, create)
}

/**
 * A feed grid state that keeps its column history while the Viewer covers the feed. The grid can
 * only rebuild columns by guessing, and a wrong guess reshuffles cards. Saved state covers process
 * death, where only the scroll position can return.
 */
@Composable
internal fun rememberRetainedFeedGridState(key: String): LazyStaggeredGridState {
    val retained = viewModel<FeedRetainedState>()
    val saver = remember(retained, key) {
        Saver<LazyStaggeredGridState, Any>(
            save = { state -> with(LazyStaggeredGridState.Saver) { save(state) } },
            restore = { saved ->
                retained.gridState(key) { requireNotNull(LazyStaggeredGridState.Saver.restore(saved)) }
            },
        )
    }
    return rememberSaveable(retained, key, saver = saver) {
        retained.gridState(key) { LazyStaggeredGridState() }
    }
}

/** The reveal order of a feed survives the Viewer, so a late post stays where it landed. */
@Composable
internal fun rememberRetainedDurationProjection(key: String): DurationFilterProjection {
    val retained = viewModel<FeedRetainedState>()
    return remember(retained, key) { retained.durationProjection(key) }
}
