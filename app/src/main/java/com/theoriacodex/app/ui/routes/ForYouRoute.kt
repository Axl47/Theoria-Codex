package com.theoriacodex.app.ui.routes

import com.theoriacodex.app.ui.components.feedDurations
import com.theoriacodex.app.ui.LocalNetworkOnline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.theoriacodex.app.recommend.ForYouCoordinator
import com.theoriacodex.app.recommend.ForYouScreen
import com.theoriacodex.app.recommend.ForYouViewModel
import com.theoriacodex.app.related.RelatedPostsLoading
import com.theoriacodex.app.related.loadedPosts
import com.theoriacodex.app.media.MediaDurationCoordinator
import com.theoriacodex.app.recommend.state.ForYouAction
import com.theoriacodex.app.recommend.state.ForYouEffect
import com.theoriacodex.app.recommend.state.ForYouUiState
import com.theoriacodex.app.viewer.PixivUgoiraClient
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.app.ui.undo.LocalUndoSlot
import com.theoriacodex.app.ui.undo.UndoAnchor
import com.theoriacodex.data.repository.FeedFabRestoreState
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey

/** App-level inputs rendered by For You or used to reconcile recommendation inputs. */
internal data class ForYouRouteConfig(
    val settings: AppSettings,
    val activeProfileLikesCount: Int,
    val availableSources: Set<SourceKey>,
    val creatorBrowsingSources: Set<SourceKey>,
    val likedPostIds: Set<PostId>,
    val resolveUnknownAnimatedDurations: Boolean,
)

/** Platform and navigation effects that remain owned by the application shell. */
internal data class ForYouRouteCallbacks(
    val onOpenViewer: suspend (ForYouEffect.OpenViewer) -> Unit,
    val onNavigateToSearch: suspend () -> Unit,
    val onOpenSourceAccounts: () -> Unit = {},
    val onShowMessage: (String) -> Unit,
    /** Offers Undo labelled `label` where `anchor` draws it; true when the user took it. */
    val onOfferUndo: suspend (label: String, anchor: UndoAnchor) -> Boolean,
    /** Makes a profile the active one in settings; For You switches to it first. */
    val onSelectProfile: (profileId: String) -> Unit,
    val onToggleLike: (Post) -> Unit,
    val onRequestSaveToCodex: (Post) -> Unit,
    val onSaveToDevice: (Post) -> Unit,
    val onGoToSearch: () -> Unit,
)

/** Navigation-scoped For You owner plus its immutable rendering boundary. */
@Composable
internal fun ForYouRoute(
    coordinator: ForYouCoordinator,
    relatedPostsLoader: RelatedPostsLoading,
    mediaDurationCoordinator: MediaDurationCoordinator,
    pixivUgoiraClient: PixivUgoiraClient?,
    config: ForYouRouteConfig,
    fabRestoreState: FeedFabRestoreState,
    onFabRestoreStateChange: (FeedFabRestoreState) -> Unit,
    callbacks: ForYouRouteCallbacks,
    onOwnerAvailable: (ForYouViewModel) -> Unit = {},
) {
    val owner = viewModel<ForYouViewModel>(
        key = FOR_YOU_ROUTE_OWNER_KEY,
        factory = ForYouViewModel.factory(
            coordinator = coordinator,
            relatedPostsLoader = relatedPostsLoader,
            networkOnline = LocalNetworkOnline.current,
            initialProfiles = config.settings.recommendationProfiles,
            initialSort = fabRestoreState.sortMode?.let { encoded ->
                com.theoriacodex.domain.model.SortMode.entries.firstOrNull { it.name == encoded }
            },
        ),
    )
    val state by owner.state.collectAsStateWithLifecycle()
    val durationPosts = remember(state.results, state.relatedPosts, state.home) {
        (state.results + state.relatedPosts.loadedPosts + state.home.sections.flatMap { it.feed.page.items } +
            state.home.mixedFeed?.page?.items.orEmpty()).distinctBy(Post::id)
    }
    val duration = rememberMediaDurationRouteBinding(
        coordinator = mediaDurationCoordinator,
        routeName = "for-you",
        ownerKey = FOR_YOU_DURATION_OWNER_KEY,
        contentIdentity = state.seedId.ifBlank { "unseeded" },
        posts = durationPosts,
        resolveInBackground = config.resolveUnknownAnimatedDurations,
    )
    val currentOnOwnerAvailable by rememberUpdatedState(onOwnerAvailable)

    SideEffect { currentOnOwnerAvailable(owner) }

    LaunchedEffect(owner, config.settings, config.activeProfileLikesCount) {
        owner.synchronizeEnvironment(
            settings = config.settings,
            activeProfileLikesCount = config.activeProfileLikesCount,
        )
    }
    LaunchedEffect(owner, config.availableSources) {
        owner.onSourceAvailabilityChanged()
    }

    // The slot's Activity-retained scope: a rotation must not drop a hidden tag's Undo.
    val undoScope = LocalUndoSlot.current.scope
    CollectRouteEffects(owner.effects) { effect ->
        handleForYouRouteEffect(effect, callbacks, owner, undoScope)
    }

    ForYouRouteContent(state, owner, duration, pixivUgoiraClient, config, fabRestoreState, onFabRestoreStateChange, callbacks)
}

@Composable
private fun ForYouRouteContent(
    state: ForYouUiState,
    owner: ForYouViewModel,
    duration: MediaDurationRouteBinding,
    pixivUgoiraClient: PixivUgoiraClient?,
    config: ForYouRouteConfig,
    fabRestoreState: FeedFabRestoreState,
    onFabRestoreStateChange: (FeedFabRestoreState) -> Unit,
    callbacks: ForYouRouteCallbacks,
) {
    val related = rememberFeedRelatedShelf(state.relatedPosts, owner.related) { posts, index ->
        owner.onAction(ForYouAction.OpenRelatedResult(index, posts))
    }
    ForYouScreen(
        state = state,
        related = related,
        likedPostIds = config.likedPostIds,
        pixivUgoiraClient = pixivUgoiraClient,
        durations = duration.owner.feedDurations(duration.states, config.resolveUnknownAnimatedDurations),
        resolvePostById = owner::resolvePost,
        onToggleLike = callbacks.onToggleLike,
        onAction = owner::onAction,
        creatorBrowsingSources = config.creatorBrowsingSources,
        onRequestSaveToCodex = callbacks.onRequestSaveToCodex,
        onSaveToDevice = callbacks.onSaveToDevice,
        onOpenSourceAccounts = callbacks.onOpenSourceAccounts,
        onSelectProfile = { profileId ->
            owner.onAction(ForYouAction.SelectProfile(profileId))
            callbacks.onSelectProfile(profileId)
        },
        onGoToSearch = callbacks.onGoToSearch,
        fabRestoreState = fabRestoreState,
        onFabRestoreStateChange = onFabRestoreStateChange,
    )
}

private const val FOR_YOU_DURATION_OWNER_KEY = "for-you-duration-owner"

private suspend fun handleForYouRouteEffect(
    effect: ForYouEffect,
    callbacks: ForYouRouteCallbacks,
    owner: ForYouViewModel,
    undoScope: CoroutineScope,
) {
    when (effect) {
        is ForYouEffect.OpenViewer -> callbacks.onOpenViewer(effect)
        ForYouEffect.NavigateToSearch -> callbacks.onNavigateToSearch()
        is ForYouEffect.ShowMessage -> callbacks.onShowMessage(effect.message)
        // Waiting on the offer must not hold back later effects, such as the next hide replacing this offer.
        is ForYouEffect.SeedHidden -> undoScope.launch {
            if (callbacks.onOfferUndo(effect.label, effect.anchor)) {
                owner.onAction(ForYouAction.UndoSeedBlacklist(effect.profileId, effect.entries))
            }
        }
        is ForYouEffect.BlacklistSeed,
        is ForYouEffect.ChangeProfile,
        is ForYouEffect.ChangeSort,
        is ForYouEffect.ChangeSource,
        is ForYouEffect.LoadNextPage,
        is ForYouEffect.RefreshFeed,
        is ForYouEffect.ReplaySearch,
        is ForYouEffect.UndoSeedBlacklist,
        -> Unit
    }
}

private const val FOR_YOU_ROUTE_OWNER_KEY = "for-you-route-owner"
