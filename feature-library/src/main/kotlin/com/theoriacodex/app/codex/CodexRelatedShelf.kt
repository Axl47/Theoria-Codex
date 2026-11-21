package com.theoriacodex.app.codex

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.theoriacodex.app.related.RelatedShelfBinding
import com.theoriacodex.app.ui.components.FeedCardEnvironment
import com.theoriacodex.app.ui.components.FeedRelatedPostsShelf
import com.theoriacodex.app.ui.components.RelatedFeedPresentation
import com.theoriacodex.app.ui.components.TwoColumnProjectedPostStaggeredGrid
import com.theoriacodex.domain.model.Post

/** The collection grid with its related shelf inserted below the post that opened it. */
@Composable
internal fun CodexRelatedPostGrid(
    shelf: RelatedShelfBinding,
    presentation: RelatedFeedPresentation,
    visiblePosts: List<Post>,
    gridState: LazyStaggeredGridState,
    footerMessage: String?,
    environment: FeedCardEnvironment,
    onOpenPostActions: (Post) -> Unit,
    postContent: @Composable (visibleIndex: Int, post: Post) -> Unit,
) {
    val visibleIndexById = remember(visiblePosts) { visiblePosts.withIndex().associate { it.value.id to it.index } }
    val controller = shelf.controller
    TwoColumnProjectedPostStaggeredGrid(
        projection = presentation.projection,
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        footerMessage = footerMessage,
        postContent = { _, post -> postContent(visibleIndexById.getValue(post.id), post) },
        shelfContent = {
            FeedRelatedPostsShelf(
                state = shelf.state,
                presentation = presentation,
                environment = environment,
                onOpenPost = shelf.onOpenPost,
                onLongPress = onOpenPostActions,
                onDismiss = { controller.clear() },
                onRetry = controller::retry,
                onPreviousPage = controller::showPreviousPage,
                onNextPage = controller::showNextPage,
            )
        },
    )
}
