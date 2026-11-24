package com.theoriacodex.app.ui.components

import com.theoriacodex.app.tags.PostTagActionSection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import com.theoriacodex.app.media.MediaDurationState
import com.theoriacodex.app.media.observedMediaDurationMs
import com.theoriacodex.app.related.RelatedPostsUiState
import com.theoriacodex.app.related.RelatedShelfBinding
import com.theoriacodex.app.search.SearchResultCard
import com.theoriacodex.app.tags.LocalPostTagSearchActions
import com.theoriacodex.app.viewer.PixivUgoiraClient
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import kotlinx.coroutines.flow.Flow

data class FeedCardEnvironment(
    val pixivUgoiraClient: PixivUgoiraClient?,
    val likedPostIds: State<Set<PostId>>,
    val acquiredDurations: State<Map<PostId, Long>>,
    val durationStateForPost: (Post) -> Flow<MediaDurationState?>,
    val onToggleLike: ((Post) -> Unit)?,
    val onViewportChanged: (Post, Boolean) -> Unit,
    val onAuthoritativeDurationKnown: (Post, Long) -> Unit,
    val resolvePostById: (suspend (PostId) -> Post?)? = null,
)

@Composable
fun rememberFeedCardEnvironment(
    pixivUgoiraClient: PixivUgoiraClient?,
    likedPostIds: Set<PostId>,
    acquiredDurations: Map<PostId, Long>,
    durations: FeedDurations,
    onToggleLike: ((Post) -> Unit)?,
    resolvePostById: (suspend (PostId) -> Post?)? = null,
): FeedCardEnvironment {
    val liked = rememberUpdatedState(likedPostIds)
    val known = rememberUpdatedState(acquiredDurations)
    val latestResolve = rememberUpdatedState(resolvePostById)
    val resolver: (suspend (PostId) -> Post?)? = if (resolvePostById == null) null else {
        remember<suspend (PostId) -> Post?> { { postId -> latestResolve.value?.invoke(postId) } }
    }
    val durationStateForPost = durations.stateForPost
    val onViewportChanged = durations.onPostVisibilityChanged
    val onDurationKnown = durations.onAuthoritativeDurationKnown
    return remember(pixivUgoiraClient, durationStateForPost, onToggleLike, onViewportChanged, onDurationKnown, resolver) {
        FeedCardEnvironment(
            pixivUgoiraClient, liked, known, durationStateForPost, onToggleLike, onViewportChanged, onDurationKnown, resolver,
        )
    }
}

@Composable
fun FeedPostCard(
    post: Post,
    environment: FeedCardEnvironment,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)?,
    showWatchHistory: Boolean = false,
    showSourceBadge: Boolean = false,
    showTitle: Boolean = true,
    metadataLabel: String? = null,
    viewedMediaNumber: Int? = null,
    mediaAspectRatio: Float? = null,
    likeEnabled: Boolean = true,
    refreshOnPreviewError: Boolean = false,
    resolvePostById: (suspend (PostId) -> Post?)? = null,
    recoverPostMedia: (suspend (Post, ImageRef) -> Post?)? = null,
    modifier: Modifier = Modifier,
) {
    val observedDurationMs = observedMediaDurationMs(post, environment.durationStateForPost)
    val acquiredDurationMs by remember(post.id, environment.acquiredDurations) {
        derivedStateOf { environment.acquiredDurations.value[post.id] }
    }
    val liked by remember(post.id, environment.likedPostIds) {
        derivedStateOf { post.id in environment.likedPostIds.value }
    }
    SearchResultCard(
        post = post,
        showWatchHistory = showWatchHistory,
        pixivUgoiraClient = environment.pixivUgoiraClient,
        acquiredDurationMs = observedDurationMs ?: acquiredDurationMs,
        showSourceBadge = showSourceBadge,
        metadataLabel = metadataLabel,
        viewedMediaNumber = viewedMediaNumber,
        mediaAspectRatio = mediaAspectRatio,
        showTitle = showTitle,
        liked = liked,
        onToggleLike = environment.onToggleLike?.takeIf { likeEnabled }?.let { toggle -> { toggle(post) } },
        resolvePostById = resolvePostById ?: environment.resolvePostById,
        recoverPostMedia = recoverPostMedia,
        refreshOnPreviewError = refreshOnPreviewError,
        onClick = onClick,
        onLongPress = onLongPress,
        onViewportChanged = { visible -> environment.onViewportChanged(post, visible) },
        onAuthoritativeDurationKnown = { durationMs -> environment.onAuthoritativeDurationKnown(post, durationMs) },
        modifier = modifier,
    )
}

@Composable
fun FeedRelatedPostsShelf(
    state: RelatedPostsUiState,
    presentation: RelatedFeedPresentation,
    environment: FeedCardEnvironment,
    onOpenPost: (posts: List<Post>, index: Int) -> Unit,
    onLongPress: (Post) -> Unit,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    recoverPostMedia: (suspend (Post, ImageRef) -> Post?)? = null,
) {
    val shelfDurations = rememberUpdatedState(presentation.knownDurationMsByPostId)
    val shelfEnvironment = remember(environment) { environment.copy(acquiredDurations = shelfDurations) }
    RelatedPostsShelf(
        state = state,
        posts = presentation.visiblePosts,
        environment = shelfEnvironment,
        recoverPostMedia = recoverPostMedia,
        onOpenPost = onOpenPost,
        onLongPress = onLongPress,
        onDismiss = onDismiss,
        onRetry = onRetry,
        onPreviousPage = onPreviousPage,
        onNextPage = onNextPage,
    )
}

@Composable
fun FeedRelatedPostsShelf(
    shelf: RelatedShelfBinding,
    presentation: RelatedFeedPresentation,
    environment: FeedCardEnvironment,
    onLongPress: (Post) -> Unit,
    recoverPostMedia: (suspend (Post, ImageRef) -> Post?)? = null,
) {
    val controller = shelf.controller
    FeedRelatedPostsShelf(
        state = shelf.state,
        presentation = presentation,
        environment = environment,
        onOpenPost = shelf.onOpenPost,
        onLongPress = onLongPress,
        onDismiss = { controller.clear() },
        onRetry = controller::retry,
        onPreviousPage = controller::showPreviousPage,
        onNextPage = controller::showNextPage,
        recoverPostMedia = recoverPostMedia,
    )
}

@Composable
fun FeedPostActionSheet(
    post: Post,
    onDismiss: () -> Unit,
    onGoToSearch: (() -> Unit)? = null,
    onToggleLike: ((Post) -> Unit)? = null,
    onSaveToDevice: ((Post) -> Unit)? = null,
    onSaveToCodex: ((Post) -> Unit)? = null,
    saveToCodexLabel: String = "Save to Codex",
    onRemoveFromCodex: (() -> Unit)? = null,
    removeLabel: String = "Remove from Codex",
    onStartOver: (() -> Unit)? = null,
    onShowRelated: (() -> Unit)? = null,
) {
    val searchActions = LocalPostTagSearchActions.current
    PostActionSheet(
        post, onDismiss, onToggleLike, onSaveToDevice, onSaveToCodex,
        saveToCodexLabel = saveToCodexLabel,
        onRemoveFromCodex = onRemoveFromCodex,
        removeLabel = removeLabel,
        onGoToSearch = onGoToSearch,
        onStartOver = onStartOver,
        onShowRelated = onShowRelated,
    ) {
        PostTagActionSection(
            post,
            onSearch = onGoToSearch?.let { goToSearch ->
                {
                    searchActions.applyDraft()
                    onDismiss()
                    goToSearch()
                }
            },
        )
    }
}
