package com.theoriacodex.app.codex

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.theoriacodex.app.media.MediaDurationKey
import com.theoriacodex.app.media.MediaDurationState
import com.theoriacodex.app.related.RelatedPostsRouteController
import com.theoriacodex.app.related.RelatedPostsUiState
import com.theoriacodex.app.search.SearchVisibilityFilters
import com.theoriacodex.app.search.UnknownAnimatedDurationPolicy
import com.theoriacodex.app.ui.components.FeedCardEnvironment
import com.theoriacodex.app.ui.components.FeedRelatedPostsShelf
import com.theoriacodex.app.ui.components.RelatedFeedPresentation
import com.theoriacodex.app.ui.components.TwoColumnProjectedPostStaggeredGrid
import com.theoriacodex.app.ui.components.rememberRelatedFeedPresentation
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId

/** A live Codex's related shelf: its current state, the owner that drives it, and how its posts open. */
internal data class CodexRelatedShelf(
    val state: RelatedPostsUiState,
    val controller: RelatedPostsRouteController,
    val onOpenPost: (posts: List<Post>, index: Int) -> Unit,
)

/** The shelf plus where it sits among the collection's filtered posts. */
internal data class CodexRelatedFeed(
    val shelf: CodexRelatedShelf,
    val presentation: RelatedFeedPresentation,
)

/** Projects [shelf] into the collection, filtering its posts by the collection's own media filters. */
@Composable
internal fun rememberCodexRelatedFeed(
    shelf: CodexRelatedShelf,
    posts: List<Post>,
    visiblePosts: List<Post>,
    filters: CodexCollectionFilters,
    likedPostIds: Set<PostId>,
    durationStates: Map<MediaDurationKey, MediaDurationState>,
    unknownDurationPolicy: UnknownAnimatedDurationPolicy,
    durationFilterActive: Boolean,
): CodexRelatedFeed {
    val visibility = remember(filters.animatedOnly, filters.animatedDurationRange) {
        SearchVisibilityFilters(animatedOnly = filters.animatedOnly, animatedDurationRange = filters.animatedDurationRange)
    }
    val presentation = rememberRelatedFeedPresentation(
        canonicalPosts = posts,
        visibleCanonicalPosts = visiblePosts,
        relatedState = shelf.state,
        visibilityFilters = visibility,
        likedPostIds = likedPostIds,
        savedPostIds = emptySet(),
        watchedPostIds = emptySet(),
        unknownAnimatedDurationPolicy = unknownDurationPolicy,
        durationStates = durationStates,
        durationFilterActive = durationFilterActive,
    )
    return CodexRelatedFeed(shelf, presentation)
}

/** The collection grid with its related shelf inserted below the post that opened it. */
@Composable
internal fun CodexRelatedPostGrid(
    related: CodexRelatedFeed,
    visiblePosts: List<Post>,
    gridState: LazyStaggeredGridState,
    footerMessage: String?,
    environment: FeedCardEnvironment,
    onOpenPostActions: (Post) -> Unit,
    postContent: @Composable (visibleIndex: Int, post: Post) -> Unit,
) {
    val visibleIndexById = remember(visiblePosts) { visiblePosts.withIndex().associate { it.value.id to it.index } }
    val controller = related.shelf.controller
    TwoColumnProjectedPostStaggeredGrid(
        projection = related.presentation.projection,
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        footerMessage = footerMessage,
        postContent = { _, post -> postContent(visibleIndexById.getValue(post.id), post) },
        shelfContent = {
            FeedRelatedPostsShelf(
                state = related.shelf.state,
                presentation = related.presentation,
                environment = environment,
                onOpenPost = related.shelf.onOpenPost,
                onLongPress = onOpenPostActions,
                onDismiss = { controller.clear() },
                onRetry = controller::retry,
                onPreviousPage = controller::showPreviousPage,
                onNextPage = controller::showNextPage,
            )
        },
    )
}
