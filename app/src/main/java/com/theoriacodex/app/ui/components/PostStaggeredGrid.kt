package com.theoriacodex.app.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridScope
import com.theoriacodex.app.ui.adaptiveFeedColumns
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridItemScope
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.theoriacodex.domain.model.Post
import com.theoriacodex.app.related.RelatedFeedEntry
import com.theoriacodex.app.related.RelatedFeedProjection
import com.theoriacodex.app.related.requestOrNull

/** Shared adaptive feed geometry (two columns on phones); each route still owns card behavior and paging policy. */
@Composable
fun TwoColumnPostStaggeredGrid(
    posts: List<Post>,
    state: LazyStaggeredGridState,
    modifier: Modifier = Modifier,
    showPagingTile: Boolean = false,
    footerMessage: String? = null,
    itemContent: @Composable LazyStaggeredGridItemScope.(index: Int, post: Post) -> Unit,
) {
    AdaptivePostGrid(state = state, modifier = modifier) {
        itemsIndexed(
            items = posts,
            key = { _, post -> "${post.id.source.name}:${post.id.sourcePostId}" },
            itemContent = itemContent,
        )
        if (showPagingTile || footerMessage != null) {
            item { FeedPagingTile(message = footerMessage) }
        }
    }
}

/** Search/FYP-only projection that inserts one full-line transient section without rewriting posts. */
@Composable
fun TwoColumnProjectedPostStaggeredGrid(
    projection: RelatedFeedProjection,
    state: LazyStaggeredGridState,
    modifier: Modifier = Modifier,
    showPagingTile: Boolean = false,
    footerMessage: String? = null,
    postContent: @Composable LazyStaggeredGridItemScope.(canonicalIndex: Int, post: Post) -> Unit,
    shelfContent: @Composable LazyStaggeredGridItemScope.(RelatedFeedEntry.ShelfEntry) -> Unit,
) {
    AdaptivePostGrid(state = state, modifier = modifier) {
        itemsIndexed(
            items = projection.entries,
            key = { _, entry ->
                when (entry) {
                    is RelatedFeedEntry.PostEntry -> "post:${entry.post.id.source.name}:${entry.post.id.sourcePostId}"
                    is RelatedFeedEntry.ShelfEntry -> {
                        val seed = entry.state.requestOrNull?.seed?.id
                        "related:${seed?.source?.name}:${seed?.sourcePostId}"
                    }
                }
            },
            span = { _, entry ->
                if (entry is RelatedFeedEntry.ShelfEntry) {
                    StaggeredGridItemSpan.FullLine
                } else {
                    StaggeredGridItemSpan.SingleLane
                }
            },
        ) { _, entry ->
            when (entry) {
                is RelatedFeedEntry.PostEntry -> postContent(entry.canonicalIndex, entry.post)
                is RelatedFeedEntry.ShelfEntry -> shelfContent(entry)
            }
        }
        if (showPagingTile || footerMessage != null) {
            item(span = StaggeredGridItemSpan.FullLine) { FeedPagingTile(message = footerMessage) }
        }
    }
}

@Composable
private fun AdaptivePostGrid(
    state: LazyStaggeredGridState,
    modifier: Modifier,
    content: LazyStaggeredGridScope.() -> Unit,
) {
    BoxWithConstraints(modifier = modifier) {
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(adaptiveFeedColumns(maxWidth.value)),
            state = state,
            modifier = Modifier.fillMaxSize(),
            verticalItemSpacing = 6.dp,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
    }
}
