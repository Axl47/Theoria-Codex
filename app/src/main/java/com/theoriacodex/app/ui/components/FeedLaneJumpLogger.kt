package com.theoriacodex.app.ui.components

import android.util.Log
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import com.theoriacodex.app.BuildConfig
import com.theoriacodex.app.feed.FeedLaneHistory
import com.theoriacodex.app.feed.FeedLanePlacement
import java.util.concurrent.ConcurrentHashMap

private const val LANE_JUMP_LOG_TAG = "TheoriaGridJump"

/** Outlives a feed's composition, so a card that changes column across the Viewer is still caught. */
private val laneHistories = ConcurrentHashMap<String, FeedLaneHistory>()

/**
 * Debug-only check for "no shown card changes column": logs every layout where one did. Read it with
 * `adb logcat -s TheoriaGridJump` to tell which interaction reshuffled a feed.
 */
@Composable
internal fun FeedLaneJumpLogger(gridId: String, state: LazyStaggeredGridState) {
    if (!BuildConfig.DEBUG) return
    LaunchedEffect(gridId, state) {
        val history = laneHistories.getOrPut(gridId) { FeedLaneHistory() }
        snapshotFlow {
            state.layoutInfo.visibleItemsInfo.map { item -> FeedLanePlacement(item.key, item.lane) }
        }.collect { placements ->
            val moved = history.record(placements)
            if (moved > 0) {
                Log.d(
                    LANE_JUMP_LOG_TAG,
                    "moved=$moved first=${state.firstVisibleItemIndex} scrolling=${state.isScrollInProgress}",
                )
            }
        }
    }
}
