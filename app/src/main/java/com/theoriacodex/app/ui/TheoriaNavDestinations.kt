package com.theoriacodex.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.theoriacodex.app.codex.CodexDetailScreen
import com.theoriacodex.app.codex.SAVED_CODEX_ID
import com.theoriacodex.app.codex.likesCodexIdForProfile
import com.theoriacodex.app.media.MediaDurationKey
import com.theoriacodex.app.media.MediaDurationRouteViewModel
import com.theoriacodex.app.media.MediaDurationState
import com.theoriacodex.app.related.RelatedPostsRouteController
import com.theoriacodex.app.related.RelatedPostsUiState
import com.theoriacodex.app.related.RelatedShelfBinding
import com.theoriacodex.app.related.requestOrNull
import com.theoriacodex.app.related.retainedLikedPostIds
import com.theoriacodex.app.settings.SettingsAction
import com.theoriacodex.app.ui.components.codexFeedFabContext
import com.theoriacodex.app.ui.components.creatorFeedFabContext
import com.theoriacodex.app.ui.routes.BrowsingDestinationStateBoundary
import com.theoriacodex.app.ui.routes.CodexDetailDestinationState
import com.theoriacodex.app.ui.routes.CodexDetailDestinationStateBoundary
import com.theoriacodex.app.ui.routes.CreatorRoute
import com.theoriacodex.app.ui.routes.CreatorRouteCallbacks
import com.theoriacodex.app.ui.routes.CreatorRouteConfig
import com.theoriacodex.app.ui.routes.SavedCodexRoute
import com.theoriacodex.app.ui.routes.ViewerDestinationState
import com.theoriacodex.app.ui.routes.ViewerDestinationStateBoundary
import com.theoriacodex.app.ui.routes.ViewerRoute
import com.theoriacodex.app.ui.routes.ViewerRouteDependencies
import com.theoriacodex.app.ui.routes.ViewerRouteEffectCallbacks
import com.theoriacodex.app.ui.routes.ViewerRouteLiveSourceSnapshot
import com.theoriacodex.app.ui.routes.ViewerRouteLiveSourceState
import com.theoriacodex.app.ui.routes.ViewerRouteRenderConfig
import com.theoriacodex.app.ui.routes.ViewerRouteScreenCallbacks
import com.theoriacodex.app.ui.routes.shareViewerPostMessage
import com.theoriacodex.app.ui.routes.virtualCodexUi
import com.theoriacodex.app.viewer.ViewerMediaPrefetcher
import com.theoriacodex.app.viewer.ViewerRelatedBinding
import com.theoriacodex.app.viewer.ViewerReturnLifetime
import com.theoriacodex.app.viewer.ViewerSession
import com.theoriacodex.app.viewer.VIEWER_MEMORY_PREFETCH_DISTANCE
import com.theoriacodex.app.viewer.prefetchViewerMedia
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.data.repository.FeedFabRestoreState
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.data.repository.followKey
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Every navigation entry. Each builds its Codex morph here, from its own and the shared scope. */
internal fun NavGraphBuilder.theoriaDestinations(shell: TheoriaShell, sharedScope: SharedTransitionScope) {
    composable(AppRoute.Home) {
        CompositionLocalProvider(LocalCodexMorph provides remember { CodexMorphScope(sharedScope, this) }) {
            HomePager(shell)
        }
    }
    composable(
        route = AppRoute.CodexDetail,
        arguments = listOf(navArgument("codexId") { type = NavType.StringType }),
    ) { entry ->
        CodexDetailEntry(shell, entry, sharedScope, this)
    }
    composable(AppRoute.CreatorProfile) { CreatorDestination(shell) }
    composable(
        AppRoute.Viewer,
        // Media owns the expansion/shrink; navigation retains both destinations.
        enterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    ) {
        ViewerReturnLifetime(this)
        ViewerDestination(shell)
    }
}

@Composable
private fun CodexDetailEntry(
    shell: TheoriaShell,
    entry: NavBackStackEntry,
    sharedScope: SharedTransitionScope,
    entryScope: AnimatedContentScope,
) {
    // Commit system Back through the same animated pop as the toolbar.
    BackHandler { shell.navController.popBackStack() }
    val codexId = requireNotNull(entry.arguments?.getString("codexId"))
    RecordCodexEntryEffect(entry, codexId, shell.data.statisticsRepository)
    val codexMorph = remember { CodexMorphScope(sharedScope, entryScope) }
    // Opaque so the screen hides the tile it grows out of.
    Box(Modifier.fillMaxSize().codexMorph(codexId, codexMorph).background(MaterialTheme.colorScheme.background)) {
        if (codexId == SAVED_CODEX_ID) {
            SavedCodexRoute(
                data = shell.data,
                sources = shell.sources,
                durations = shell.features.mediaDurationCoordinator,
                restoreRegistry = shell.feedFabRestoreRegistry,
                onBack = { shell.navController.popBackStack() },
                onOpenCodex = { shell.navController.navigate(AppRoute.codexDetail(it)) },
                onOpenViewer = shell::openCodexViewer,
                onOpenCodexActions = shell.overlays::openCollectionActions,
                resolvePost = { shell.viewerRouteWorkflow.resolvePost(it, ViewerStreamSource.CODEX) },
                onToggleLike = { shell.toggleLikeAndSyncCodex(it) },
            )
        } else {
            CodexDetailDestination(shell, codexId)
        }
    }
}

@Composable
private fun CodexDetailDestination(shell: TheoriaShell, codexId: String) {
    val fabContext = codexFeedFabContext(codexId)
    val fabRestoreState = shell.feedFabRestoreRegistry.state(fabContext) ?: return
    val sortMode = fabRestoreState.sortMode?.let { encoded ->
        CodexSortMode.entries.firstOrNull { mode -> mode.name == encoded }
    } ?: CodexSortMode.NEWEST_SAVED
    val durationOwner = viewModel<MediaDurationRouteViewModel>(
        key = "codex-detail-duration-$codexId",
        factory = MediaDurationRouteViewModel.factory(shell.features.mediaDurationCoordinator, "codex-detail:$codexId"),
    )
    val durationStates by durationOwner.states.collectAsStateWithLifecycle()
    CodexDetailDestinationStateBoundary(
        codexId = codexId,
        sortMode = sortMode,
        fabRestoreState = fabRestoreState,
        data = shell.data,
        sources = shell.sources,
    ) { state ->
        SideEffect {
            durationOwner.synchronize(
                identity = codexId,
                posts = state.posts,
                resolveInBackground = state.resolveUnknownAnimatedDurations,
            )
        }
        CodexDetailContent(shell, codexId, sortMode, fabContext, fabRestoreState, state, durationOwner, durationStates)
    }
}

@Composable
private fun CodexDetailContent(
    shell: TheoriaShell,
    codexId: String,
    sortMode: CodexSortMode,
    fabContext: String,
    fabRestoreState: FeedFabRestoreState,
    state: CodexDetailDestinationState,
    durationOwner: MediaDurationRouteViewModel,
    durationStates: Map<MediaDurationKey, MediaDurationState>,
) {
    val routes = shell.routes
    val commands = shell.owners.codexCommands
    val virtualUi = virtualCodexUi(
        state, fabRestoreState, { shell.feedFabRestoreRegistry.update(fabContext, it) },
        { shell.navController.popBackStack() }, { shell.overlays.showFollowedCreators = true },
        shell.owners.savedSearches::rename, shell.owners.savedSearches::remove,
    )
    val liveOwner = state.followedOwner ?: state.savedSearchOwner
    CodexDetailScreen(
        codexName = state.codex?.name,
        onTitleLongPress = { shell.overlays.codexActionTargetId = codexId },
        customHeader = virtualUi?.header,
        likedPostIds = state.likedPostIds,
        onToggleLike = liveOwner?.let { owner ->
            { post -> shell.toggleLikeAndSyncCodex(post) { outcome -> owner.related.onLikeCommitted(post, outcome) } }
        },
        related = liveOwner?.let { owner -> rememberRelatedShelfBinding(shell, owner.relatedState, owner.related) },
        customFilters = virtualUi?.filters,
        customFiltersActive = virtualUi?.filtersActive == true,
        emptyMessage = virtualUi?.emptyMessage,
        loading = state.loading,
        posts = state.posts,
        sortMode = sortMode,
        availableSources = state.availableSources,
        creatorBrowsingSources = state.creatorBrowsingSources,
        pixivUgoiraClient = shell.sources.pixivUgoiraClient,
        resolveUnknownAnimatedDurations = state.resolveUnknownAnimatedDurations,
        durationStates = durationStates,
        durationStateForPost = durationOwner::observeState,
        tagVideoCountProvider = { source, tag -> shell.features.search.tagVideoCount(source, tag) },
        fetchTagVideoCounts = { source, tags -> shell.features.search.fetchTagVideoCounts(source, tags) },
        onDurationFilterChanged = durationOwner::onFilterChanged,
        onDurationPostVisibilityChanged = durationOwner::onPostVisibilityChanged,
        onDurationEnvironmentChanged = durationOwner::onEnvironmentChanged,
        onAuthoritativeDurationKnown = durationOwner::publishPlayerDuration,
        resolvePostById = { postId -> shell.viewerRouteWorkflow.resolvePost(postId, ViewerStreamSource.CODEX) },
        onOpenViewer = { posts, index -> shell.openCodexViewer(codexId, posts, index) },
        onRemovePosts = { posts -> commands.remove(codexId, state.items, posts) },
        onAddPostsToAnotherCodex = { posts -> shell.pendingSave.request(posts, excludedCodexId = codexId) },
        onSavePostToDevice = { post ->
            shell.transfers.downloads.submitPosts(listOf(post), originLabel = "Codex · ${state.codex?.name ?: "Collection"}")
            shell.transfers.showDownloads = true
        },
        onPostUrlCopied = { shell.recordPostUrlCopy() },
        onOpenCreatorProfile = shell::launchOpenCreator,
        onOpenLegacyCreatorProfile = shell::launchOpenCreator,
        onAddIncludeTerm = routes.addSearchIncludeTerm,
        onAddExcludeTerm = routes.addSearchExcludeTerm,
        onRemoveIncludeTerm = routes.removeSearchIncludeTerm,
        onRemoveExcludeTerm = routes.removeSearchExcludeTerm,
        onFavoriteTagLongPress = shell.addFavoriteTag,
        onGoToSearch = { shell.navigation.goToSearch() },
        onBack = { shell.navController.popBackStack() },
        onDeleteCodex = { commands.delete(codexId, state.activeProfile.profileId) },
        isLikesCodex = codexId == likesCodexIdForProfile(state.activeProfile.profileId),
        fabRestoreState = fabRestoreState,
        onFabRestoreStateChange = { updated -> shell.feedFabRestoreRegistry.update(fabContext, updated) },
    )
}

@Composable
private fun CreatorDestination(shell: TheoriaShell) {
    val routes = shell.routes
    BrowsingDestinationStateBoundary(shell.data, shell.sources) { state ->
        val creator = shell.pendingCreatorProfile ?: return@BrowsingDestinationStateBoundary
        val creatorIdentity = creator.profileId ?: creator.uploadsQuery ?: creator.displayName
        val fabContext = creatorFeedFabContext(creator.source.id, creatorIdentity)
        val fabRestoreState = shell.feedFabRestoreRegistry.state(fabContext) ?: return@BrowsingDestinationStateBoundary
        CreatorRoute(
            coordinator = shell.features.creatorProfile,
            relatedPostsLoader = shell.features.relatedPosts,
            mediaDurationCoordinator = shell.features.mediaDurationCoordinator,
            pixivUgoiraClient = shell.sources.pixivUgoiraClient,
            config = CreatorRouteConfig(
                pendingLikePostIds = shell.pendingLikePostIds[state.activeProfile.profileId].orEmpty(),
                followed = state.settings.followedCreators.any { it.creator.followKey() == creator.followKey() },
                activeCreator = creator,
                availableSources = state.availableSources,
                likedPostIds = state.likedPostIds,
                savedPostIds = state.savedPostIds,
                resolveUnknownAnimatedDurations = state.settings.contentFilters.resolveUnknownAnimatedDurations,
            ),
            fabRestoreState = fabRestoreState,
            onFabRestoreStateChange = { updated -> shell.feedFabRestoreRegistry.update(fabContext, updated) },
            callbacks = CreatorRouteCallbacks(
                onToggleFollow = shell.owners.follows::toggle,
                onOpenViewer = { effect ->
                    val likeAction = shell.creatorLikeAction()
                    shell.viewerSessions.retain(
                        shell.viewerRouteWorkflow.prepareResumableSession(ViewerSession(
                            posts = effect.posts,
                            context = effect.context,
                            liveSearchBinding = effect.liveSearchBinding,
                            searchVisibilityFilters = effect.visibilityFilters,
                            onToggleLike = likeAction,
                            relatedBinding = ViewerRelatedBinding(
                                ViewerStreamSource.CREATOR_PROFILE, routes.creatorBinding.current?.currentState()?.queryHash.orEmpty(),
                            ),
                        )),
                    )
                    shell.navController.navigate(AppRoute.Viewer) {
                        launchSingleTop = !effect.liveSearchBinding
                    }
                },
                onNavigateBack = { shell.navController.popBackStack() },
                onToggleLike = { post -> shell.creatorLikeAction()(post) },
                onRequestSaveToCodex = { post -> shell.requestSaveToCodex(post) },
                onSaveToDevice = shell::requestSaveToDevice,
                onPostUrlCopied = { shell.recordPostUrlCopy() },
                onOpenUrl = { url -> openInBrowser(shell.appContext, url) },
                onAddIncludeTerm = routes.addSearchIncludeTerm,
                onAddExcludeTerm = routes.addSearchExcludeTerm,
                onRemoveIncludeTerm = routes.removeSearchIncludeTerm,
                onRemoveExcludeTerm = routes.removeSearchExcludeTerm,
            ),
            onOwnerAvailable = routes.creatorBinding::publish,
        )
    }
}

@Composable
private fun ViewerDestination(shell: TheoriaShell) {
    val routes = shell.routes
    ViewerDestinationStateBoundary(
        data = shell.data,
        sources = shell.sources,
        searchOwner = routes.searchOwner,
        forYouOwner = routes.forYouOwner,
        creatorOwner = routes.creatorOwner,
    ) { state ->
        ViewerRoute(
            dependencies = ViewerRouteDependencies(
                onCodexUsageTargetChanged = { target ->
                    if (shell.navController.currentDestination?.route == AppRoute.Viewer) {
                        shell.features.appUsageTracker.onCodexTargetChanged(target)
                    }
                },
                sessionRetentionOwner = shell.viewerSessions,
                canonicalizePost = { post -> shell.features.offlineMedia?.withoutOfflineLocations(post) ?: post },
                postResolver = shell.viewerRouteWorkflow,
                mediaPrefetcher = ViewerMediaPrefetcher { _, media, distance ->
                    prefetchViewerMedia(
                        shell.appContext,
                        media,
                        state.browsing.settings.viewer.videoQuality,
                        keepDecodedInMemory = distance <= VIEWER_MEMORY_PREFETCH_DISTANCE,
                    )
                },
                mediaDurationCoordinator = shell.features.mediaDurationCoordinator,
                restoreSession = shell.viewerRouteWorkflow::restoreSession,
            ),
            renderConfig = ViewerRouteRenderConfig(
                playbackMode = state.browsing.settings.viewer.playbackMode,
                videoQuality = state.browsing.settings.viewer.videoQuality,
                pixivUgoiraClient = shell.sources.pixivUgoiraClient,
                tagVideoCountProvider = shell.features.search::tagVideoCount,
                fetchTagVideoCounts = shell.features.search::fetchTagVideoCounts,
                invertMultiImageScrollDirection = state.browsing.settings.viewer.invertMultiImageScrollDirection,
                instantSeek = state.browsing.settings.viewer.instantSeek,
                likedPostIds = state.browsing.likedPostIds,
                creatorBrowsingSources = state.browsing.creatorBrowsingSources,
            ),
            liveSourceState = viewerLiveSourceState(state, shell.pendingLikePostIds[state.browsing.activeProfile.profileId].orEmpty()),
            effectCallbacks = viewerEffectCallbacks(shell),
            screenCallbacks = viewerScreenCallbacks(shell),
        )
    }
}

private fun viewerLiveSourceState(
    state: ViewerDestinationState,
    pendingLikes: Set<com.theoriacodex.domain.model.PostId>,
) = ViewerRouteLiveSourceState(
    search = ViewerRouteLiveSourceSnapshot(
        queryHash = state.search.query.appliedQueryHash.takeIf(String::isNotBlank),
        results = state.search.content.results,
        relatedPosts = state.search.relatedPosts,
        retainedLikedPostIds = state.search.relatedPosts.retainedLikedPostIds + pendingLikes,
        canLoadMore = state.search.content.canLoadMore,
        loadingMore = state.search.loadingMore,
    ),
    forYou = ViewerRouteLiveSourceSnapshot(
        queryHash = "for_you:${state.forYou.seedId}",
        relatedPosts = state.forYou.relatedPosts,
        results = state.forYou.results,
        canLoadMore = state.forYou.canLoadMore,
        loadingMore = state.forYou.isPaging,
    ),
    creatorProfile = ViewerRouteLiveSourceSnapshot(
        queryHash = state.creator.queryHash,
        results = state.creator.results,
        relatedPosts = state.creator.relatedPosts,
        retainedLikedPostIds = state.creator.relatedPosts.retainedLikedPostIds + pendingLikes,
        canLoadMore = state.creator.canLoadMore,
        loadingMore = state.creator.isPaging,
    ),
    likedPostIds = state.browsing.likedPostIds,
    savedPostIds = state.browsing.savedPostIds,
    watchedPostIds = state.browsing.watchedPostIds,
    unknownAnimatedDurationPolicy = state.browsing.unknownAnimatedDurationPolicy,
)

/** Names where a Viewer download came from, by the stream the session was launched from. */
private suspend fun TheoriaShell.viewerDownloadOrigin(): String {
    val launch = activeViewerOwner?.session?.value?.context
    return when (launch?.streamSource) {
        ViewerStreamSource.SEARCH -> "Search"
        ViewerStreamSource.FOR_YOU -> "FYP"
        ViewerStreamSource.CODEX -> {
            val id = launch.queryHash.removePrefix("codex:")
            val name = data.codexRepository.observeCodices().first().firstOrNull { it.codexId == id }?.name
            "Codex · ${name ?: "Collection"}"
        }
        ViewerStreamSource.CREATOR_PROFILE -> "Creator profile"
        ViewerStreamSource.RECENTS -> "Recents"
        ViewerStreamSource.RELATED -> "Related posts"
        null -> "Viewer"
    }
}

private fun viewerEffectCallbacks(shell: TheoriaShell) = ViewerRouteEffectCallbacks(
    onSavePost = { post, context ->
        shell.requestSaveToCodex(
            post = post,
            fromForYou = context.streamSource == ViewerStreamSource.FOR_YOU,
            searchQuery = shell.activeViewerOwner?.session?.value
                ?.takeIf { it.context.queryHash == context.queryHash }?.saveQuery,
        )
    },
    onSharePost = { post ->
        shareViewerPostMessage(shell.appContext, post) { shell.recordPostUrlCopy() }?.let { message -> shell.toast(message) }
    },
    onDownloadMedia = { request ->
        if (request == null) {
            shell.snackbarHostState.showSnackbar("Media is still loading.")
        } else {
            val origin = shell.viewerDownloadOrigin()
            shell.transfers.downloads.submitViewerMedia(request.post, request.media, request.pageIndex, request.totalPages, origin)
            shell.transfers.showDownloads = true
        }
    },
    onToggleLike = { post -> shell.toggleLikeAndSyncCodex(post) },
    onOpenCreatorProfile = { creator -> shell.openCreatorProfile(creator) },
    onApplyTag = { post, term, excluded ->
        if (excluded) {
            shell.routes.addSearchExcludeTerm(post, term.toSearchTerm())
        } else {
            shell.routes.addSearchIncludeTerm(post, term.toSearchTerm())
        }
    },
    onRecoverMedia = shell.viewerRouteWorkflow::recoverMedia,
    onLoadMore = shell.viewerRouteWorkflow::loadMore,
    onDismiss = {
        shell.features.search.setViewerLaunchContext(null)
        shell.navController.popBackStack()
    },
    onRestorationUnavailable = {
        shell.features.search.setViewerLaunchContext(null)
        shell.toast("Viewer session expired")
        shell.navController.popBackStack()
    },
)

private fun viewerScreenCallbacks(shell: TheoriaShell) = ViewerRouteScreenCallbacks(
    onDismissRelatedPosts = shell.viewerRouteWorkflow::dismissRelatedPosts,
    onRetryRelatedPosts = shell.viewerRouteWorkflow::retryRelatedPosts,
    onPlaybackModeSelected = { shell.owners.settings.onAction(SettingsAction.SetViewerPlaybackMode(it)) },
    onOwnerChanged = { owner -> shell.activeViewerOwner = owner },
    onVisiblePostChanged = { post, viewedMediaNumber, session ->
        shell.viewerRouteWorkflow.recordVisiblePost(post, viewedMediaNumber, session)
    },
    onVisibleMediaChanged = { post, viewedMediaNumber, session ->
        shell.viewerRouteWorkflow.recordVisibleMediaProgress(post, viewedMediaNumber, session)
    },
    onOpenInBrowser = { post ->
        post.pageUrl?.takeIf { it.isNotBlank() }?.let { url -> openInBrowser(shell.appContext, url) }
    },
    onRemoveIncludeTerm = shell.routes.removeSearchIncludeTerm,
    onRemoveExcludeTerm = shell.routes.removeSearchExcludeTerm,
    onFavoriteTagLongPress = shell.addFavoriteTag,
    onGoToSearch = {
        shell.scope.launch { shell.features.search.setViewerLaunchContext(null) }
        shell.navigation.goToSearch()
    },
    onOpenCreatorFallback = shell::launchOpenCreator,
)

/** Binds a shelf whose owner no feed pages, opening its posts in the Viewer as a related stream. */
@Composable
internal fun rememberRelatedShelfBinding(
    shell: TheoriaShell,
    state: StateFlow<RelatedPostsUiState>,
    controller: RelatedPostsRouteController,
): RelatedShelfBinding {
    val relatedState by state.collectAsStateWithLifecycle()
    return remember(controller, relatedState) {
        RelatedShelfBinding(relatedState, controller) { posts, index ->
            relatedState.requestOrNull?.seed?.let { seed -> shell.openRelatedViewer(seed, posts, index) }
        }
    }
}
