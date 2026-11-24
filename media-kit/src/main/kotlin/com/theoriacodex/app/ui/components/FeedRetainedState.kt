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

internal class FeedRetainedState : ViewModel() {
    private val durationProjections = mutableMapOf<String, DurationFilterProjection>()
    private val gridStates = mutableMapOf<String, LazyStaggeredGridState>()
    private val frameCaches = mutableMapOf<String, FrameCache<*, *>>()

    fun durationProjection(key: String): DurationFilterProjection =
        durationProjections.getOrPut(key) { DurationFilterProjection() }

    fun gridState(key: String, create: () -> LazyStaggeredGridState): LazyStaggeredGridState =
        gridStates.getOrPut(key, create)

    fun forgetExcept(prefix: String, keep: Set<String>) {
        val kept = { key: String -> key in keep || keep.any { key.startsWith("$it:") } }
        listOf(durationProjections, gridStates, frameCaches).forEach { states -> states.keys.removeAll { it.startsWith(prefix) && !kept(it) } }
    }

    @Suppress("UNCHECKED_CAST")
    fun <E, T> frameCache(key: String): FrameCache<E, T> = frameCaches.getOrPut(key) { FrameCache<E, T>() } as FrameCache<E, T>
}

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

@Composable
fun ForgetRetainedFeedsEffect(prefix: String, keep: Set<String>) {
    val retained = viewModel<FeedRetainedState>()
    LaunchedEffect(retained, prefix, keep) { retained.forgetExcept(prefix, keep) }
}

@Composable
internal fun rememberRetainedDurationProjection(key: String): DurationFilterProjection {
    val retained = viewModel<FeedRetainedState>()
    return remember(retained, key) { retained.durationProjection(key) }
}

@Composable
internal fun <E, T> rememberRetainedFrameCache(key: String): FrameCache<E, T> {
    val retained = viewModel<FeedRetainedState>()
    return remember(retained, key) { retained.frameCache(key) }
}
