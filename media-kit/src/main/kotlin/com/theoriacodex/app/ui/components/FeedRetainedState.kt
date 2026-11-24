package com.theoriacodex.app.ui.components

import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
    private val frameCaches = mutableMapOf<String, FrameCache<*, *>>()

    fun durationProjection(key: String): DurationFilterProjection =
        durationProjections.getOrPut(key) { DurationFilterProjection() }

    fun gridState(key: String, create: () -> LazyStaggeredGridState): LazyStaggeredGridState =
        gridStates.getOrPut(key, create)

    /** Forgets every feed whose key starts with [prefix], apart from those in [keep]. */
    fun forgetExcept(prefix: String, keep: Set<String>) {
        listOf(durationProjections, gridStates, frameCaches).forEach { states -> states.keys.removeAll { it.startsWith(prefix) && it !in keep } }
    }

    /** One [rememberComputedOffMain] call site owns each key, so its element and value types never change. */
    @Suppress("UNCHECKED_CAST")
    fun <E, T> frameCache(key: String): FrameCache<E, T> = frameCaches.getOrPut(key) { FrameCache<E, T>() } as FrameCache<E, T>
}

/**
 * A feed grid state that keeps its column history while the Viewer covers the feed. The grid can
 * only rebuild columns by guessing, and a wrong guess reshuffles cards. Saved state covers process
 * death, where only the scroll position can return.
 */
@Composable
fun rememberRetainedFeedGridState(key: String): LazyStaggeredGridState {
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

/**
 * For a screen whose feeds come and go, each under its own key: forgets the retained state of every feed keyed under
 * [prefix] but those in [keep], since a feed that can never show again would hold its posts until the screen leaves.
 */
@Composable
fun ForgetRetainedFeedsEffect(prefix: String, keep: Set<String>) {
    val retained = viewModel<FeedRetainedState>()
    LaunchedEffect(retained, prefix, keep) { retained.forgetExcept(prefix, keep) }
}

/** The reveal order of a feed survives the Viewer, so a late post stays where it landed. */
@Composable
internal fun rememberRetainedDurationProjection(key: String): DurationFilterProjection {
    val retained = viewModel<FeedRetainedState>()
    return remember(retained, key) { retained.durationProjection(key) }
}

/** The last frame of a [rememberComputedOffMain] call, kept while the Viewer covers the feed. */
@Composable
internal fun <E, T> rememberRetainedFrameCache(key: String): FrameCache<E, T> {
    val retained = viewModel<FeedRetainedState>()
    return remember(retained, key) { retained.frameCache(key) }
}
