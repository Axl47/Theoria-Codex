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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.rememberSaveable
import com.theoriacodex.app.search.FeedImagePreloadEffect
import com.theoriacodex.app.viewer.LocalViewerReturnGrid
import com.theoriacodex.app.viewer.ViewerReturnShelfId
import com.theoriacodex.app.viewer.ViewerReturnGridEffect
import java.util.UUID
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.theoriacodex.domain.model.Post
import androidx.compose.runtime.remember
import com.theoriacodex.app.related.RelatedFeedEntry
import com.theoriacodex.app.related.RelatedFeedProjection
import com.theoriacodex.app.related.requestOrNull

/**
 * For You, Creator and Codex's one feed grid over a [FeedView]: its visible posts with the related shelf placed among
 * them. [post] gets each post's index in [FeedView.visiblePosts], the list the Viewer opens; [shelf] draws the shelf.
 * Each screen still owns its cards and paging policy.
 */
@Composable
fun FeedGrid(
    view: FeedView,
    state: LazyStaggeredGridState,
    footerMessage: String?,
    modifier: Modifier = Modifier.fillMaxSize(),
    showPagingTile: Boolean = false,
    shelf: @Composable LazyStaggeredGridItemScope.(RelatedFeedEntry.ShelfEntry) -> Unit,
    post: @Composable (visibleIndex: Int, post: Post) -> Unit,
) {
    val visibleIndexById = remember(view.visiblePosts) { view.visiblePosts.withIndex().associate { it.value.id to it.index } }
    TwoColumnProjectedPostStaggeredGrid(
        projection = view.related.projection,
        state = state,
        modifier = modifier,
        showPagingTile = showPagingTile,
        footerMessage = footerMessage,
        postContent = { _, entry -> post(visibleIndexById.getValue(entry.id), entry) },
        shelfContent = shelf,
    )
}

/** Feed projection that inserts one full-line transient section without rewriting posts. */
@Composable
fun TwoColumnProjectedPostStaggeredGrid(
    projection: RelatedFeedProjection,
    state: LazyStaggeredGridState,
    modifier: Modifier = Modifier,
    showPagingTile: Boolean = false,
    footerMessage: String? = null,
    postContent: @Composable LazyStaggeredGridItemScope.(canonicalIndex: Int, post: Post) -> Unit,
    shelfContent: @Composable LazyStaggeredGridItemScope.(RelatedFeedEntry.ShelfEntry) -> Unit,
    footerContent: (@Composable () -> Unit)? = null,
) {
    val shelfIds = remember(projection) {
        projection.entries.map { (it as? RelatedFeedEntry.ShelfEntry)?.state?.requestOrNull?.seed?.id?.let(ViewerReturnShelfId::Related) }
    }
    val posts = remember(projection) { projection.entries.map { (it as? RelatedFeedEntry.PostEntry)?.post } }
    AdaptivePostGrid(posts = posts, state = state, modifier = modifier, shelfIds = shelfIds) {
        itemsIndexed(
            items = projection.entries,
            key = { _, entry ->
                when (entry) {
                    is RelatedFeedEntry.PostEntry -> "post:${entry.post.id.source.id}:${entry.post.id.sourcePostId}"
                    is RelatedFeedEntry.ShelfEntry -> {
                        val seed = entry.state.requestOrNull?.seed?.id
                        "related:${seed?.source?.id}:${seed?.sourcePostId}"
                    }
                }
            },
            // Single-lane posts and the full-line related shelf must not reuse each other's compositions.
            contentType = { _, entry -> entry.javaClass },
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
        if (footerContent != null) {
            item(key = "feed-footer", span = StaggeredGridItemSpan.FullLine) { footerContent() }
        }
    }
}

/**
 * Every feed's grid: adaptive columns, Viewer return, and card image preloading. [posts] holds one entry per grid
 * item, null for items that are not posts (headings, shelves, footers), so both find each post by its grid position.
 */
@Composable
fun AdaptivePostGrid(
    posts: List<Post?>,
    state: LazyStaggeredGridState,
    modifier: Modifier,
    shelfIds: List<ViewerReturnShelfId?> = emptyList(),
    content: LazyStaggeredGridScope.() -> Unit,
) {
    val postIds = remember(posts) { posts.map { it?.id } }
    FeedImagePreloadEffect(posts, state)
    val gridId = rememberSaveable { UUID.randomUUID().toString() }
    ViewerReturnGridEffect(gridId, postIds, state, shelfIds)
    FeedLaneJumpLogger(gridId, state)
    CompositionLocalProvider(LocalViewerReturnGrid provides gridId) {
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
}
