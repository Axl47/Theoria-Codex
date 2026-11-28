package com.theoriacodex.app.ui.components

import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridLayoutInfo
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

@Composable
fun SeenPostsEffect(grid: LazyStaggeredGridState, posts: List<Post>, onSeen: suspend (Set<PostId>) -> Unit) {
    val byKey = remember(posts) { posts.associateBy { feedPostItemKey(it.id) } }
    val currentPosts by rememberUpdatedState(byKey)
    val report by rememberUpdatedState(onSeen)
    LaunchedEffect(grid) {
        val reported = mutableSetOf<PostId>()
        val pending = mutableSetOf<PostId>()
        suspend fun flush() {
            if (pending.isEmpty()) return
            val batch = pending.toSet()
            pending.clear()
            reported += batch
            report(batch)
        }
        try {
            snapshotFlow { grid.isScrollInProgress to grid.layoutInfo.mostlyVisibleKeys().mapNotNullTo(mutableSetOf()) { currentPosts[it]?.id } }
                .collect { (scrolling, seen) ->
                    pending += seen - reported
                    if (!scrolling) flush()
                }
        } finally {
            withContext(NonCancellable) { flush() }
        }
    }
}

internal fun LazyStaggeredGridLayoutInfo.mostlyVisibleKeys(): Set<Any> = visibleItemsInfo.filter { item ->
    val top = maxOf(item.offset.y, viewportStartOffset)
    val bottom = minOf(item.offset.y + item.size.height, viewportEndOffset)
    item.size.height > 0 && (bottom - top) * 2 >= item.size.height
}.mapTo(mutableSetOf()) { it.key }
