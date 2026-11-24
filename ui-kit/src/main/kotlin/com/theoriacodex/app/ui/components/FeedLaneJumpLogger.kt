package com.theoriacodex.app.ui.components

import android.util.Log
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import com.theoriacodex.uikit.BuildConfig
import com.theoriacodex.app.feed.FeedLaneHistory
import com.theoriacodex.app.feed.FeedLanePlacement
import java.util.concurrent.ConcurrentHashMap

private const val LANE_JUMP_LOG_TAG = "TheoriaGridJump"

private val laneHistories = ConcurrentHashMap<String, FeedLaneHistory>()

@Composable
fun FeedLaneJumpLogger(gridId: String, state: LazyStaggeredGridState) {
    if (!BuildConfig.DEBUG) return
    LaunchedEffect(gridId, state) {
        val history = laneHistories.getOrPut(gridId) { FeedLaneHistory() }
        snapshotFlow {
            state.layoutInfo.visibleItemsInfo.map { item -> FeedLanePlacement(item.key, item.lane, item.size.height) }
        }.collect { placements ->
            val changes = history.record(placements)
            if (changes.any) {
                Log.d(
                    LANE_JUMP_LOG_TAG,
                    "grid=${gridId.take(8)} moved=${changes.moved} resized=${changes.resized} " +
                        "first=${state.firstVisibleItemIndex} scrolling=${state.isScrollInProgress}",
                )
            }
        }
    }
}
