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
import androidx.compose.ui.layout.layoutBounds
import androidx.compose.ui.unit.dp
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import androidx.compose.runtime.remember
import com.theoriacodex.app.related.RelatedFeedEntry
import com.theoriacodex.app.related.RelatedFeedProjection
import com.theoriacodex.app.related.requestOrNull

@Composable
fun FeedGrid(
    view: FeedView,
    state: LazyStaggeredGridState,
    footerMessage: String?,
    modifier: Modifier = Modifier.fillMaxSize(),
    showPagingTile: Boolean = false,
    divider: FeedGridDivider? = null,
    shelf: @Composable LazyStaggeredGridItemScope.(RelatedFeedEntry.ShelfEntry) -> Unit,
    post: @Composable (visibleIndex: Int, post: Post) -> Unit,
) {
    TwoColumnProjectedPostStaggeredGrid(
        projection = view.related.projection,
        state = state,
        modifier = modifier,
        showPagingTile = showPagingTile,
        footerMessage = footerMessage,
        divider = divider,
        postContent = { visibleIndex, entry -> post(visibleIndex, entry) },
        shelfContent = shelf,
    )
}

class FeedGridDivider(val beforePost: PostId, val content: @Composable () -> Unit)

@Composable
fun TwoColumnProjectedPostStaggeredGrid(
    projection: RelatedFeedProjection,
    state: LazyStaggeredGridState,
    modifier: Modifier = Modifier,
    showPagingTile: Boolean = false,
    footerMessage: String? = null,
    divider: FeedGridDivider? = null,
    postContent: @Composable LazyStaggeredGridItemScope.(visibleIndex: Int, post: Post) -> Unit,
    shelfContent: @Composable LazyStaggeredGridItemScope.(RelatedFeedEntry.ShelfEntry) -> Unit,
    footerContent: (@Composable () -> Unit)? = null,
) {
    val split = remember(projection, divider?.beforePost) {
        divider?.beforePost?.let { id ->
            projection.entries.indexOfFirst { (it as? RelatedFeedEntry.PostEntry)?.post?.id == id }.takeIf { it > 0 }
        }
    }
    val shelfIds = remember(projection, split) {
        projection.entries.map { (it as? RelatedFeedEntry.ShelfEntry)?.state?.requestOrNull?.seed?.id?.let(ViewerReturnShelfId::Related) }
            .withGapAt(split)
    }
    val posts = remember(projection, split) { projection.entries.map { (it as? RelatedFeedEntry.PostEntry)?.post }.withGapAt(split) }
    AdaptivePostGrid(posts = posts, state = state, modifier = modifier, shelfIds = shelfIds) {
        if (split == null || divider == null) {
            projectedEntries(projection.entries, postContent, shelfContent)
        } else {
            projectedEntries(projection.entries.subList(0, split), postContent, shelfContent)
            item(key = "feed-divider", contentType = "feed-divider", span = StaggeredGridItemSpan.FullLine) { divider.content() }
            projectedEntries(projection.entries.subList(split, projection.entries.size), postContent, shelfContent)
        }
        if (showPagingTile || footerMessage != null) {
            item(span = StaggeredGridItemSpan.FullLine) { FeedPagingTile(message = footerMessage) }
        }
        if (footerContent != null) {
            item(key = "feed-footer", span = StaggeredGridItemSpan.FullLine) { footerContent() }
        }
    }
}

private fun <T> List<T?>.withGapAt(index: Int?): List<T?> = if (index == null) this else toMutableList().apply { add(index, null) }

private fun LazyStaggeredGridScope.projectedEntries(
    entries: List<RelatedFeedEntry>,
    postContent: @Composable LazyStaggeredGridItemScope.(visibleIndex: Int, post: Post) -> Unit,
    shelfContent: @Composable LazyStaggeredGridItemScope.(RelatedFeedEntry.ShelfEntry) -> Unit,
) {
    itemsIndexed(
        items = entries,
        key = { _, entry ->
            when (entry) {
                is RelatedFeedEntry.PostEntry -> "post:${entry.post.id.source.id}:${entry.post.id.sourcePostId}"
                is RelatedFeedEntry.ShelfEntry -> {
                    val seed = entry.state.requestOrNull?.seed?.id
                    "related:${seed?.source?.id}:${seed?.sourcePostId}"
                }
            }
        },
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
            is RelatedFeedEntry.PostEntry -> postContent(entry.visibleIndex, entry.post)
            is RelatedFeedEntry.ShelfEntry -> shelfContent(entry)
        }
    }
}

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
    val viewport = rememberFeedViewport()
    CompositionLocalProvider(LocalViewerReturnGrid provides gridId, LocalFeedViewport provides viewport) {
        BoxWithConstraints(modifier = modifier) {
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(adaptiveFeedColumns(maxWidth.value)),
                state = state,
                modifier = Modifier.fillMaxSize().layoutBounds(viewport.bounds),
                verticalItemSpacing = 6.dp,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                content = content,
            )
        }
    }
}
