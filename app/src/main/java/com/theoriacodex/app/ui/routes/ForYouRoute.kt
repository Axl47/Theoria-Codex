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

/** App-level inputs the For You deck is built from or drawn with. */
internal data class ForYouRouteConfig(
    val settings: AppSettings,
    val availableSources: Set<SourceKey>,
    val creatorBrowsingSources: Set<SourceKey>,
    /** The active profile's marks: the Mix does not deal a post it already liked or saved. */
    val marks: FeedMarks,
    val resolveUnknownAnimatedDurations: Boolean,
    /** Whether a tag trail's search is already a saved search. */
    val isSearchSaved: (Query) -> Boolean,
    /**
     * Whether For You is on screen or the tabs are heading to it. The owner is told what to deal from only then, so a
     * tab change that merely passes over For You asks the sources for nothing.
     */
    val shown: Boolean,
)

/** What the owner and its cards are built with. */
internal class ForYouRouteServices(
    val coordinator: ForYouCoordinator,
    /** Read once, when the owner is created. */
    val wiring: () -> ForYouDeckWiring,
    val mediaDurationCoordinator: MediaDurationCoordinator,
    val pixivUgoiraClient: PixivUgoiraClient?,
    val recoverPostMedia: suspend (Post, ImageRef) -> Post?,
)

/** Platform and navigation effects that remain owned by the application shell. */
internal class ForYouRouteCallbacks(
    /** Opens the Viewer on a post of a level's feed. */
    val onOpenPost: (level: ForYouLevel, post: PostId, scrollOffsetHint: Int) -> Unit,
    val onOpenRelated: (seed: Post, posts: List<Post>, index: Int) -> Unit,
    val onPickCodex: (post: Post, onPick: (ChosenCodex) -> Unit) -> Unit,
    val onSaveSearch: (Query) -> Unit,
    val onOpenSourceAccounts: () -> Unit,
    val onShowMessage: (String) -> Unit,
    /** Makes a profile the active one in settings, which the deck follows. */
    val onSelectProfile: (profileId: String) -> Unit,
    val onToggleLike: (Post) -> Unit,
    val onRequestSaveToCodex: (Post) -> Unit,
    val onGoToSearch: () -> Unit,
)

/**
 * The navigation-scoped For You owner and its screen. The feed's restore state keeps the source, sort, media filter and
 * the deck or grid choice; the owner itself keeps nothing, so a recreated one deals a fresh Mix from them.
 */
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
    val liked = remember(config.marks) { config.marks.liked + config.marks.pendingLikes }
    val support = TriageCardSupport(
        environment = rememberFeedCardEnvironment(
            services.pixivUgoiraClient, liked, emptyMap(), durations, callbacks.onToggleLike, owner::resolvePost,
        ),
        resolvePostById = owner::resolvePost,
        recoverPostMedia = services.recoverPostMedia,
    )
    val related = rememberFeedRelatedShelf(viewerFeed.relatedPosts, owner.related) { posts, index ->
        viewerFeed.relatedPosts.requestOrNull?.let { request -> callbacks.onOpenRelated(request.seed, posts, index) }
    }
    ForYouDeckScreen(
        owner = owner,
        config = ForYouScreenConfig(
            sortMode, fabRestoreState.animatedOnly, fabRestoreState.grid, liked, config.creatorBrowsingSources, durations, support,
            config.isSearchSaved,
        ),
        related = related,
        callbacks = deckCallbacks(callbacks, fabRestoreState, onFabRestoreStateChange),
    )
}

/** Hands the owner what it deals from, again whenever any of it changes, while For You is [ForYouRouteConfig.shown]. */
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

/** The screen's callbacks: the shell's, plus the choices the feed's restore state keeps. */
private fun deckCallbacks(
    callbacks: ForYouRouteCallbacks,
    restore: FeedFabRestoreState,
    onRestoreChange: (FeedFabRestoreState) -> Unit,
) = ForYouDeckCallbacks(
    onSelectProfile = callbacks.onSelectProfile,
    onSelectSource = { source -> onRestoreChange(restore.copy(source = source?.id)) },
    onSelectSort = { sort -> onRestoreChange(restore.copy(sortMode = sort.name)) },
    onAnimatedOnlyChange = { animatedOnly -> onRestoreChange(restore.copy(animatedOnly = animatedOnly)) },
    // One change: two would each start from the state before the other.
    onResetFilters = { onRestoreChange(restore.copy(sortMode = null, animatedOnly = false)) },
    onGridChange = { grid -> onRestoreChange(restore.copy(grid = grid)) },
    onOpenPost = callbacks.onOpenPost,
    onPickCodex = callbacks.onPickCodex,
    onRequestSaveToCodex = callbacks.onRequestSaveToCodex,
    onSaveSearch = callbacks.onSaveSearch,
    onToggleLike = callbacks.onToggleLike,
    onOpenSourceAccounts = callbacks.onOpenSourceAccounts,
    onGoToSearch = callbacks.onGoToSearch,
)

private const val FOR_YOU_DURATION_OWNER_KEY = "for-you-duration-owner"
private const val FOR_YOU_ROUTE_OWNER_KEY = "for-you-route-owner"
