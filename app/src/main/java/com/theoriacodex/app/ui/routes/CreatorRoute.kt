package com.theoriacodex.app.ui.routes

import com.theoriacodex.app.ui.NewPostsSeen
import com.theoriacodex.domain.model.AccountFeed
import com.theoriacodex.app.ui.components.feedDurations
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.theoriacodex.app.related.RelatedPostsLoading
import com.theoriacodex.app.related.loadedPosts
import com.theoriacodex.app.creator.CreatorProfileCoordinator
import com.theoriacodex.app.creator.CreatorProfileScreen
import com.theoriacodex.app.creator.CreatorProfileViewModel
import com.theoriacodex.app.media.MediaDurationCoordinator
import com.theoriacodex.app.creator.state.CreatorAction
import com.theoriacodex.app.creator.state.CreatorEffect
import com.theoriacodex.app.creator.state.CreatorUiState
import com.theoriacodex.app.ui.LocalNetworkOnline
import com.theoriacodex.app.ui.components.FeedMarks
import com.theoriacodex.app.ui.undo.LocalUndoSlot
import com.theoriacodex.app.ui.undo.UndoSlot
import com.theoriacodex.app.viewer.PixivUgoiraClient
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.data.repository.FeedFabRestoreState

internal data class CreatorRouteConfig(
    val activeCreator: CreatorProfile?,
    val followed: Boolean = false,
    val availableSources: Set<SourceKey>,
    val marks: FeedMarks,
    val resolveUnknownAnimatedDurations: Boolean,
    val newPosts: NewPostsSeen? = null,
)

internal data class CreatorRouteCallbacks(
    val onOpenViewer: suspend (CreatorEffect.OpenViewer) -> Unit,
    val onNavigateBack: () -> Unit,
    val onToggleLike: (Post) -> Unit,
    val onToggleFollow: (CreatorProfile, List<Post>, UndoSlot) -> Unit = { _, _, _ -> },
    val onOpenUrl: (String) -> Unit,
)

@Composable
internal fun CreatorRoute(
    coordinator: CreatorProfileCoordinator,
    relatedPostsLoader: RelatedPostsLoading,
    mediaDurationCoordinator: MediaDurationCoordinator,
    pixivUgoiraClient: PixivUgoiraClient?,
    config: CreatorRouteConfig,
    fabRestoreState: FeedFabRestoreState,
    onFabRestoreStateChange: (FeedFabRestoreState) -> Unit,
    callbacks: CreatorRouteCallbacks,
    onOwnerAvailable: (CreatorProfileViewModel) -> Unit = {},
) {
    val owner = viewModel<CreatorProfileViewModel>(
        factory = CreatorProfileViewModel.factory(coordinator, relatedPostsLoader, LocalNetworkOnline.current),
    )
    val state by owner.state.collectAsStateWithLifecycle()
    val durationPosts = remember(state.results, state.relatedPosts) {
        (state.results + state.relatedPosts.loadedPosts).distinctBy(Post::id)
    }
    val duration = rememberMediaDurationRouteBinding(
        coordinator = mediaDurationCoordinator,
        routeName = "creator",
        ownerKey = CREATOR_DURATION_OWNER_KEY,
        contentIdentity = state.queryHash ?: "no-creator",
        posts = durationPosts,
        resolveInBackground = config.resolveUnknownAnimatedDurations,
    )
    val currentOnOwnerAvailable by rememberUpdatedState(onOwnerAvailable)

    SideEffect { currentOnOwnerAvailable(owner) }

    LaunchedEffect(owner, config.activeCreator) {
        config.activeCreator?.let { creator ->
            if (owner.state.value.creator != creator) {
                owner.onAction(CreatorAction.OpenCreator(creator))
            }
        }
    }
    LaunchedEffect(owner, config.availableSources) {
        owner.onSourceAvailabilityChanged()
    }

    CollectRouteEffects(owner.effects) { effect ->
        when (effect) {
            is CreatorEffect.OpenViewer -> callbacks.onOpenViewer(effect)
            CreatorEffect.NavigateBack -> callbacks.onNavigateBack()
            is CreatorEffect.LoadPage,
            -> Unit
        }
    }

    CreatorRouteContent(state, owner, duration, pixivUgoiraClient, config, fabRestoreState, onFabRestoreStateChange, callbacks)
}

@Composable
private fun CreatorRouteContent(
    state: CreatorUiState,
    owner: CreatorProfileViewModel,
    duration: MediaDurationRouteBinding,
    pixivUgoiraClient: PixivUgoiraClient?,
    config: CreatorRouteConfig,
    fabRestoreState: FeedFabRestoreState,
    onFabRestoreStateChange: (FeedFabRestoreState) -> Unit,
    callbacks: CreatorRouteCallbacks,
) {
    val related = rememberFeedRelatedShelf(state.relatedPosts, owner.related) { posts, index ->
        owner.onAction(CreatorAction.OpenRelatedResult(index, posts))
    }
    val undo = LocalUndoSlot.current
    val seen by owner.seenAtOpen.collectAsStateWithLifecycle()
    val newPosts = remember(config.newPosts, state.selectedFeed) {
        config.newPosts?.let { if (state.selectedFeed == AccountFeed.UPLOADS) it else it.withoutFollowed() }
    }
    val newPostIds = remember(state.results, newPosts) { newPosts?.among(state.results).orEmpty() }
    CreatorProfileScreen(
        related = related,
        followed = config.followed,
        onToggleFollow = { state.creator?.let { callbacks.onToggleFollow(it, state.uploads, undo) } },
        seenAtOpen = seen?.takeIf { config.followed && it.creator == state.creator }?.seenPostIds,
        newPostIds = newPostIds,
        onNewPostsSeen = newPosts?.onSeen,
        state = state,
        marks = config.marks,
        pixivUgoiraClient = pixivUgoiraClient,
        durations = duration.owner.feedDurations(duration.states, config.resolveUnknownAnimatedDurations),
        resolvePostById = owner::resolvePost,
        onToggleLike = callbacks.onToggleLike,
        onAction = owner::onAction,
        onOpenUrl = callbacks.onOpenUrl,
        fabRestoreState = fabRestoreState,
        onFabRestoreStateChange = onFabRestoreStateChange,
    )
}

private const val CREATOR_DURATION_OWNER_KEY = "creator-duration-owner"
