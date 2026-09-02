package com.theoriacodex.app.search

import androidx.compose.runtime.Composable
import com.theoriacodex.app.media.MediaDurationState
import com.theoriacodex.app.related.RelatedPostsUiState
import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.ui.components.RelatedFeedPresentation
import com.theoriacodex.app.ui.components.RelatedPostsShelf
import com.theoriacodex.app.viewer.PixivUgoiraClient
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import kotlinx.coroutines.flow.Flow

@Composable
internal fun SearchRelatedPostsShelf(
    state: RelatedPostsUiState,
    presentation: RelatedFeedPresentation,
    likedPostIds: Set<PostId>,
    pixivUgoiraClient: PixivUgoiraClient?,
    durationStateForPost: (Post) -> Flow<MediaDurationState?>,
    recoverPostMedia: suspend (Post, ImageRef) -> Post?,
    onToggleLike: ((Post) -> Unit)?,
    onAction: (SearchAction) -> Unit,
    onLongPress: (Post) -> Unit,
    onViewportChanged: (Post, Boolean) -> Unit,
    onAuthoritativeDurationKnown: (Post, Long) -> Unit,
) {
    RelatedPostsShelf(
        state = state,
        posts = presentation.visiblePosts,
        likedPostIds = likedPostIds,
        pixivUgoiraClient = pixivUgoiraClient,
        acquiredDurations = presentation.knownDurationMsByPostId,
        durationStateForPost = durationStateForPost,
        recoverPostMedia = recoverPostMedia,
        onToggleLike = { post -> onToggleLike?.invoke(post) },
        onOpenPost = { posts, index -> onAction(SearchAction.OpenRelatedResult(index, posts)) },
        onLongPress = onLongPress,
        onDismiss = { onAction(SearchAction.DismissRelatedPosts) },
        onRetry = { onAction(SearchAction.RetryRelatedPosts) },
        onViewportChanged = onViewportChanged,
        onAuthoritativeDurationKnown = onAuthoritativeDurationKnown,
    )
}
