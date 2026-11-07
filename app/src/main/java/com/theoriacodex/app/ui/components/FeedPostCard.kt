package com.theoriacodex.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.theoriacodex.app.media.MediaDurationState
import com.theoriacodex.app.media.observedMediaDurationMs
import com.theoriacodex.app.related.RelatedPostsUiState
import com.theoriacodex.app.search.SearchResultCard
import com.theoriacodex.app.tags.LocalPostTagSearchActions
import com.theoriacodex.app.tags.PostBoundTagSection
import com.theoriacodex.app.viewer.PixivUgoiraClient
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.Flow

/**
 * What every tile of one feed shares: its player, like state, and duration reporting. Likes and known
 * durations are held as state so one like or newly acquired duration recomposes only the tile it concerns.
 */
internal data class FeedCardEnvironment(
    val pixivUgoiraClient: PixivUgoiraClient?,
    val likedPostIds: State<Set<PostId>>,
    val acquiredDurations: State<Map<PostId, Long>>,
    val durationStateForPost: (Post) -> Flow<MediaDurationState?>,
    val onToggleLike: ((Post) -> Unit)?,
    val onViewportChanged: (Post, Boolean) -> Unit,
    val onAuthoritativeDurationKnown: (Post, Long) -> Unit,
)

/** Keeps one environment while its callbacks are unchanged, so visible tiles can skip recomposition. */
@Composable
internal fun rememberFeedCardEnvironment(
    pixivUgoiraClient: PixivUgoiraClient?,
    likedPostIds: Set<PostId>,
    acquiredDurations: Map<PostId, Long>,
    durationStateForPost: (Post) -> Flow<MediaDurationState?>,
    onToggleLike: ((Post) -> Unit)?,
    onViewportChanged: (Post, Boolean) -> Unit,
    onAuthoritativeDurationKnown: (Post, Long) -> Unit,
): FeedCardEnvironment {
    val liked = rememberUpdatedState(likedPostIds)
    val durations = rememberUpdatedState(acquiredDurations)
    return remember(pixivUgoiraClient, durationStateForPost, onToggleLike, onViewportChanged, onAuthoritativeDurationKnown) {
        FeedCardEnvironment(
            pixivUgoiraClient, liked, durations, durationStateForPost,
            onToggleLike, onViewportChanged, onAuthoritativeDurationKnown,
        )
    }
}

/** One feed tile, showing the best duration known so far and reporting events against its post. */
@Composable
internal fun FeedPostCard(
    post: Post,
    environment: FeedCardEnvironment,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    showWatchHistory: Boolean = false,
    showSourceBadge: Boolean = false,
    mediaAspectRatio: Float? = null,
    resolvePostById: (suspend (PostId) -> Post?)? = null,
    recoverPostMedia: (suspend (Post, ImageRef) -> Post?)? = null,
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
        mediaAspectRatio = mediaAspectRatio,
        liked = liked,
        onToggleLike = environment.onToggleLike?.let { toggle -> { toggle(post) } },
        resolvePostById = resolvePostById,
        recoverPostMedia = recoverPostMedia,
        onClick = onClick,
        onLongPress = onLongPress,
        onViewportChanged = { visible -> environment.onViewportChanged(post, visible) },
        onAuthoritativeDurationKnown = { durationMs -> environment.onAuthoritativeDurationKnown(post, durationMs) },
    )
}

/** The feed's related-posts shelf; each feed supplies only what its own actions do. */
@Composable
internal fun FeedRelatedPostsShelf(
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

/** A feed post's action sheet, with its tag section bound to that post. */
@Composable
internal fun FeedPostActionSheet(
    post: Post,
    creatorBrowsingSources: Set<SourceKey>,
    onDismiss: () -> Unit,
    onSaveToDevice: (Post) -> Unit,
    onSaveToCodex: (Post) -> Unit,
    onOpenCreatorProfile: (CreatorProfile) -> Unit,
    onOpenLegacyCreatorProfile: (Post) -> Unit,
    onGoToSearch: (() -> Unit)?,
    onPostUrlCopied: (Post) -> Unit,
    onAddIncludeTerm: (Post, SearchTerm) -> Boolean,
    onAddExcludeTerm: (Post, SearchTerm) -> Boolean,
    onRemoveIncludeTerm: (Post, SearchTerm) -> Unit,
    onRemoveExcludeTerm: (Post, SearchTerm) -> Unit,
    onFavoriteTagLongPress: ((SourceKey, String) -> Unit)?,
    tagVideoCountProvider: (SourceKey, String) -> Int? = { _, _ -> null },
    fetchTagVideoCounts: suspend (SourceKey, List<String>) -> Map<String, Int?> = { _, _ -> emptyMap() },
    saveToCodexLabel: String = "Save to Codex",
    onRemoveFromCodex: (() -> Unit)? = null,
    onStartOver: (() -> Unit)? = null,
    onShowRelated: (() -> Unit)? = null,
) {
    val searchActions = LocalPostTagSearchActions.current
    PostActionSheet(
        post, creatorBrowsingSources, onDismiss, { onSaveToDevice(post) },
        onSaveToCodex = { onSaveToCodex(post) },
        saveToCodexLabel = saveToCodexLabel,
        onRemoveFromCodex = onRemoveFromCodex,
        onOpenCreatorProfile = onOpenCreatorProfile,
        onOpenLegacyCreatorProfile = { onOpenLegacyCreatorProfile(post) },
        onGoToSearch = onGoToSearch,
        onStartOver = onStartOver,
        onPostUrlCopied = onPostUrlCopied,
        onShowRelated = onShowRelated,
    ) {
        PostBoundTagSection(
            post, onAddIncludeTerm, onAddExcludeTerm, onRemoveIncludeTerm, onRemoveExcludeTerm,
            onFavoriteTagLongPress, tagVideoCountProvider, fetchTagVideoCounts,
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
