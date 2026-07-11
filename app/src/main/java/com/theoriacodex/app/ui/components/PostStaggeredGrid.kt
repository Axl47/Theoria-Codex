package com.theoriacodex.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridItemScope
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.theoriacodex.domain.model.Post

/** Shared two-column feed geometry; each route still owns card behavior and paging policy. */
@Composable
fun TwoColumnPostStaggeredGrid(
    posts: List<Post>,
    state: LazyStaggeredGridState,
    modifier: Modifier = Modifier,
    showPagingTile: Boolean = false,
    itemContent: @Composable LazyStaggeredGridItemScope.(index: Int, post: Post) -> Unit,
) {
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(2),
        state = state,
        modifier = modifier,
        verticalItemSpacing = 6.dp,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(
            items = posts,
            key = { _, post -> "${post.id.source.name}:${post.id.sourcePostId}" },
            itemContent = itemContent,
        )
        if (showPagingTile) {
            item { FeedPagingTile() }
        }
    }
}
