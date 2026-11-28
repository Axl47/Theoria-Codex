package com.theoriacodex.app.ui.components

import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridLayoutInfo
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import com.theoriacodex.app.ui.LocalTabShown
import com.theoriacodex.domain.model.PostId
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

@Composable
fun SeenPostsEffect(
    grid: LazyStaggeredGridState,
    tracked: Set<PostId>,
    onSeen: suspend (Set<PostId>) -> Unit,
    covered: () -> Boolean = { false },
) {
    val tabShown = LocalTabShown.current
    val hidden by rememberUpdatedState(covered)
    val byKey = remember(tracked) { tracked.associateBy(::feedPostItemKey) }
    val targets by rememberUpdatedState(byKey)
    val report by rememberUpdatedState(onSeen)
    LaunchedEffect(grid) {
        val reported = mutableSetOf<PostId>()
        val pending = mutableSetOf<PostId>()
        suspend fun flush() {
            if (pending.isEmpty()) return
            val batch = pending.toSet()
            pending.clear()
            reported += batch
            withContext(NonCancellable) { report(batch) }
        }
        try {
            snapshotFlow {
                val current = targets
                if (current.isEmpty() || !tabShown() || hidden()) false to emptySet<PostId>()
                else grid.isScrollInProgress to grid.layoutInfo.mostlyVisibleKeys().mapNotNullTo(mutableSetOf()) { current[it] }
            }
                .collect { (scrolling, seen) ->
                    pending += seen - reported
                    if (!scrolling) flush()
                }
        } finally {
            flush()
        }
    }
}

internal fun LazyStaggeredGridLayoutInfo.mostlyVisibleKeys(): Set<Any> = visibleItemsInfo.filter { item ->
    val visible = minOf(item.offset.y + item.size.height, viewportEndOffset) - maxOf(item.offset.y, viewportStartOffset)
    val target = minOf(item.size.height, viewportEndOffset - viewportStartOffset)
    visible > 0 && visible * 2 >= target
}.mapTo(mutableSetOf()) { it.key }
