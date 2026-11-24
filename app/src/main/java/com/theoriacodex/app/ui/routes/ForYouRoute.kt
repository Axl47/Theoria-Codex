package com.theoriacodex.app.ui.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.theoriacodex.app.codex.ChosenCodex
import com.theoriacodex.app.media.MediaDurationCoordinator
import com.theoriacodex.app.recommend.ForYouCoordinator
import com.theoriacodex.app.recommend.ForYouDeckCallbacks
import com.theoriacodex.app.recommend.ForYouDeckScreen
import com.theoriacodex.app.recommend.ForYouDeckViewModel
import com.theoriacodex.app.recommend.ForYouDeckWiring
import com.theoriacodex.app.recommend.ForYouEnvironment
import com.theoriacodex.app.recommend.ForYouLevel
import com.theoriacodex.app.recommend.ForYouScreenConfig
import com.theoriacodex.app.related.loadedPosts
import com.theoriacodex.app.related.requestOrNull
import com.theoriacodex.app.triage.TriageCardSupport
import com.theoriacodex.app.ui.ReportLaunchContent
import com.theoriacodex.app.ui.TopLevelDestination
import com.theoriacodex.app.ui.components.FeedMarks
import com.theoriacodex.app.ui.components.feedDurations
import com.theoriacodex.app.ui.components.rememberFeedCardEnvironment
import com.theoriacodex.app.viewer.PixivUgoiraClient
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.FeedFabRestoreState
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey

internal data class ForYouRouteConfig(
    val settings: AppSettings,
    val availableSources: Set<SourceKey>,
    val creatorBrowsingSources: Set<SourceKey>,
    val marks: FeedMarks,
    val resolveUnknownAnimatedDurations: Boolean,
    val isSearchSaved: (Query) -> Boolean,
    val shown: Boolean,
)

internal class ForYouRouteServices(
    val coordinator: ForYouCoordinator,
    val wiring: () -> ForYouDeckWiring,
    val mediaDurationCoordinator: MediaDurationCoordinator,
    val pixivUgoiraClient: PixivUgoiraClient?,
    val recoverPostMedia: suspend (Post, ImageRef) -> Post?,
)

internal class ForYouRouteCallbacks(
    val onOpenPost: (level: ForYouLevel, post: PostId, scrollOffsetHint: Int) -> Unit,
    val onOpenRelated: (seed: Post, posts: List<Post>, index: Int) -> Unit,
    val onPickCodex: (post: Post, onPick: (ChosenCodex) -> Unit) -> Unit,
    val onSaveSearch: (Query) -> Unit,
    val onOpenSourceAccounts: () -> Unit,
    val onShowMessage: (String) -> Unit,
    val onSelectProfile: (profileId: String) -> Unit,
    val onToggleLike: (Post) -> Unit,
    val onRequestSaveToCodex: (Post) -> Unit,
    val onGoToSearch: () -> Unit,
)

@Composable
internal fun ForYouRoute(
    services: ForYouRouteServices,
    config: ForYouRouteConfig,
    fabRestoreState: FeedFabRestoreState,
    onFabRestoreStateChange: (FeedFabRestoreState) -> Unit,
    callbacks: ForYouRouteCallbacks,
    onOwnerAvailable: (ForYouDeckViewModel) -> Unit = {},
) {
    val owner = viewModel<ForYouDeckViewModel>(key = FOR_YOU_ROUTE_OWNER_KEY) {
        ForYouDeckViewModel(services.coordinator, services.wiring())
    }
    val currentOnOwnerAvailable by rememberUpdatedState(onOwnerAvailable)
    SideEffect { currentOnOwnerAvailable(owner) }

    val sortMode = remember(fabRestoreState.sortMode) { SortMode.entries.firstOrNull { it.name == fabRestoreState.sortMode } ?: SortMode.NEWEST }
    FollowForYouEnvironment(owner, config, fabRestoreState, sortMode)
    val showMessage by rememberUpdatedState(callbacks.onShowMessage)
    LaunchedEffect(owner) { owner.messages.collect { showMessage(it) } }

    val levels by owner.levels.collectAsStateWithLifecycle()
    val level = levels.last()
    val feed by level.feed.collectAsStateWithLifecycle()
    ReportLaunchContent(TopLevelDestination.ForYou, drawn = feed.posts.isNotEmpty() || feed.pageFailed || !feed.loadingMore && !feed.canLoadMore)
    val viewerFeed by owner.viewerFeedChanges.collectAsStateWithLifecycle(owner.viewerFeed)
    val durationPosts = remember(feed.posts, viewerFeed.relatedPosts) {
        (feed.posts + viewerFeed.relatedPosts.loadedPosts).distinctBy(Post::id)
    }
    val duration = rememberMediaDurationRouteBinding(
        coordinator = services.mediaDurationCoordinator,
        routeName = "for-you",
        ownerKey = FOR_YOU_DURATION_OWNER_KEY,
        contentIdentity = level.id,
        posts = durationPosts,
        resolveInBackground = config.resolveUnknownAnimatedDurations,
    )
    val durations = duration.owner.feedDurations(duration.states, config.resolveUnknownAnimatedDurations)
    val liked = remember(config.marks.liked, config.marks.pendingLikes) { config.marks.liked + config.marks.pendingLikes }
    val environment = rememberFeedCardEnvironment(
        services.pixivUgoiraClient, liked, emptyMap(), durations, callbacks.onToggleLike, owner::resolvePost,
    )
    val support = remember(environment, owner, services.recoverPostMedia) {
        TriageCardSupport(environment, resolvePostById = owner::resolvePost, recoverPostMedia = services.recoverPostMedia)
    }
    val related = rememberFeedRelatedShelf(viewerFeed.relatedPosts, owner.related) { posts, index ->
        viewerFeed.relatedPosts.requestOrNull?.let { request -> callbacks.onOpenRelated(request.seed, posts, index) }
    }
    val animatedOnly = fabRestoreState.animatedOnly
    val grid = fabRestoreState.grid
    val screenConfig = remember(sortMode, animatedOnly, grid, config.creatorBrowsingSources, durations, support, config.isSearchSaved) {
        ForYouScreenConfig(sortMode, animatedOnly, grid, config.creatorBrowsingSources, durations, support, config.isSearchSaved)
    }
    ForYouDeckScreen(
        owner = owner,
        config = screenConfig,
        related = related,
        callbacks = rememberDeckCallbacks(callbacks, fabRestoreState, onFabRestoreStateChange),
    )
}

@Composable
private fun FollowForYouEnvironment(owner: ForYouDeckViewModel, config: ForYouRouteConfig, restore: FeedFabRestoreState, sortMode: SortMode) {
    val selectedSource = remember(restore.source, config.availableSources) { config.availableSources.firstOrNull { it.id == restore.source } }
    val kept = remember(config.marks) { config.marks.liked + config.marks.saved + config.marks.pendingLikes }
    LaunchedEffect(owner, config.shown, config.settings, config.availableSources, selectedSource, sortMode, restore.animatedOnly, restore.grid, kept) {
        if (!config.shown) return@LaunchedEffect
        owner.synchronize(
            ForYouEnvironment(config.settings, selectedSource, sortMode, restore.animatedOnly, restore.grid, kept, config.creatorBrowsingSources),
        )
    }
}

@Composable
private fun rememberDeckCallbacks(
    callbacks: ForYouRouteCallbacks,
    restore: FeedFabRestoreState,
    onRestoreChange: (FeedFabRestoreState) -> Unit,
): ForYouDeckCallbacks {
    val current by rememberUpdatedState(callbacks)
    val currentRestore by rememberUpdatedState(restore)
    val currentOnRestoreChange by rememberUpdatedState(onRestoreChange)
    return remember {
        val change = { update: FeedFabRestoreState.() -> FeedFabRestoreState -> currentOnRestoreChange(currentRestore.update()) }
        ForYouDeckCallbacks(
            onSelectProfile = { current.onSelectProfile(it) },
            onSelectSource = { source -> change { copy(source = source?.id) } },
            onSelectSort = { sort -> change { copy(sortMode = sort.name) } },
            onAnimatedOnlyChange = { animatedOnly -> change { copy(animatedOnly = animatedOnly) } },
            onResetFilters = { change { copy(sortMode = null, animatedOnly = false) } },
            onGridChange = { grid -> change { copy(grid = grid) } },
            onOpenPost = { level, post, scrollOffsetHint -> current.onOpenPost(level, post, scrollOffsetHint) },
            onPickCodex = { post, onPick -> current.onPickCodex(post, onPick) },
            onRequestSaveToCodex = { current.onRequestSaveToCodex(it) },
            onSaveSearch = { current.onSaveSearch(it) },
            onToggleLike = { current.onToggleLike(it) },
            onOpenSourceAccounts = { current.onOpenSourceAccounts() },
            onGoToSearch = { current.onGoToSearch() },
        )
    }
}

private const val FOR_YOU_DURATION_OWNER_KEY = "for-you-duration-owner"
private const val FOR_YOU_ROUTE_OWNER_KEY = "for-you-route-owner"
