package com.theoriacodex.app.ui

import com.theoriacodex.app.series.ExtensionsDestination
import com.theoriacodex.app.series.extensionsArguments
import com.theoriacodex.app.series.seriesArguments
import com.theoriacodex.app.series.SeriesPageDestination
import kotlinx.coroutines.flow.emptyFlow
import com.theoriacodex.app.ui.components.feedDurations
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import com.theoriacodex.app.BuildConfig
import com.theoriacodex.app.codex.CodexDetailScreen
import com.theoriacodex.app.codex.CodexHeaderActions
import com.theoriacodex.app.codex.CodexUndoAnchors
import com.theoriacodex.app.codex.SAVED_CODEX_ID
import com.theoriacodex.app.media.CodexOfflineRemovalUndo
import com.theoriacodex.app.media.MediaDurationStates
import com.theoriacodex.app.media.MediaDurationRouteViewModel
import com.theoriacodex.app.related.RelatedPostsRouteController
import com.theoriacodex.app.related.RelatedPostsUiState
import com.theoriacodex.app.related.RelatedShelfBinding
import com.theoriacodex.app.related.requestOrNull
import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.settings.SettingsAction
import com.theoriacodex.app.tags.LocalPostTagSearchActions
import com.theoriacodex.app.tags.PostTagSearchActions
import com.theoriacodex.app.ui.components.codexFeedFabContext
import com.theoriacodex.app.ui.components.creatorFeedFabContext
import com.theoriacodex.app.ui.routes.BrowsingDestinationStateBoundary
import com.theoriacodex.app.ui.routes.CodexDetailDestinationState
import com.theoriacodex.app.ui.routes.CodexDetailDestinationStateBoundary
import com.theoriacodex.app.ui.routes.CreatorProfileStateSaver
import com.theoriacodex.app.ui.routes.CreatorRoute
import com.theoriacodex.app.ui.routes.CreatorRouteCallbacks
import com.theoriacodex.app.ui.routes.CreatorRouteConfig
import com.theoriacodex.app.ui.routes.SavedBackHeader
import com.theoriacodex.app.ui.routes.SavedCodexRoute
import com.theoriacodex.app.ui.routes.ViewerDestinationState
import com.theoriacodex.app.ui.routes.ViewerDestinationStateBoundary
import com.theoriacodex.app.ui.routes.ViewerRoute
import com.theoriacodex.app.ui.routes.ViewerRouteDependencies
import com.theoriacodex.app.ui.routes.ViewerRouteEffectCallbacks
import com.theoriacodex.app.ui.routes.ViewerRouteLiveSourceState
import com.theoriacodex.app.ui.routes.virtualCodexUi
import com.theoriacodex.app.ui.trails.forLevel
import com.theoriacodex.app.ui.trails.isTrailLevel
import com.theoriacodex.app.ui.trails.rememberViewerLevel
import com.theoriacodex.app.viewer.ViewerCoveredLifecycle
import com.theoriacodex.app.viewer.ViewerReturnLifetime
import com.theoriacodex.app.viewer.ViewerReturnOriginLifetime
import com.theoriacodex.app.viewer.ViewerViewModel
import com.theoriacodex.app.viewer.ViewerScreenCallbacks
import com.theoriacodex.app.viewer.ViewerScreenConfig
import com.theoriacodex.app.viewer.ViewerSeries
import com.theoriacodex.app.viewer.viewerMediaPrefetcher
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.data.repository.SettingChoices
import com.theoriacodex.data.repository.FeedFabRestoreState
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.data.repository.followKey
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal fun NavGraphBuilder.theoriaDestinations(
    shell: TheoriaShell,
    sharedScope: SharedTransitionScope,
    homeChrome: @Composable (@Composable (Modifier) -> Unit) -> Unit,
) {
    opaqueScreen(AppRoute.Home, reachesBottomEdge = true) {
        val codexMorph = remember { CodexMorphScope(sharedScope, this) }
        homeChrome { modifier ->
            Box(modifier) {
                CompositionLocalProvider(LocalCodexMorph provides codexMorph) { HomePager(shell) }
            }
        }
    }
    composable(
        route = AppRoute.CodexDetail,
        arguments = listOf(navArgument("codexId") { type = NavType.StringType }),
    ) { entry ->
        CodexDetailEntry(shell, entry, sharedScope, this)
    }
    opaqueScreen(AppRoute.CreatorProfile) { CreatorDestination(shell) }
    composable(AppRoute.Viewer) { entry ->
        val top by shell.navController.currentBackStackEntryAsState()
        ViewerReturnLifetime(this) { shell.navController.currentDestination?.route != AppRoute.Viewer && !entry.isTrailLevel() }
        ViewerCoveredLifecycle(covered = top.let { it != null && it.id != entry.id && it.destination.route == AppRoute.Viewer }) {
            Box(Modifier.padding(screenBottomInsets.asPaddingValues()), propagateMinConstraints = true) { ViewerDestination(shell, entry) }
        }
    }
    opaqueScreen(AppRoute.Settings) { SettingsScreenDestination(shell) }
    opaqueScreen(AppRoute.Series, seriesArguments) { entry -> SeriesPageDestination(shell, entry) }
    opaqueScreen(AppRoute.Extensions, extensionsArguments) { entry -> ExtensionsDestination(shell, entry) }
    opaqueScreen(AppRoute.Triage, triageArguments) { entry -> TriageDestination(shell, entry.triageRequest()) }
}

@Composable
private fun CodexDetailEntry(
    shell: TheoriaShell,
    entry: NavBackStackEntry,
    sharedScope: SharedTransitionScope,
    entryScope: AnimatedContentScope,
) {
    BackHandler { shell.navController.popBackStack() }
    val codexId = requireNotNull(entry.arguments?.getString("codexId"))
    RecordCodexEntryEffect(entry, codexId, shell.data.statisticsRepository)
    val codexMorph = remember { CodexMorphScope(sharedScope, entryScope) }
    Box(
        Modifier.fillMaxSize().codexMorph(codexId, codexMorph).background(MaterialTheme.colorScheme.background)
            .padding(screenBottomInsets.asPaddingValues()),
    ) {
        if (codexId == SAVED_CODEX_ID) {
            SavedCodexRoute(
                data = shell.data,
                sources = shell.sources,
                features = shell.features,
                restoreRegistry = shell.feedFabRestoreRegistry,
                header = { SavedBackHeader { shell.navController.popBackStack() } },
                onOpenCodex = { shell.navController.navigate(AppRoute.codexDetail(it)) },
                onOpenViewer = shell::openCodexViewer,
                onOpenCodexActions = shell.overlays::openCollectionActions,
                resolvePost = { shell.viewerRouteWorkflow.resolvePost(it, ViewerStreamSource.CODEX) },
                onGoToSearch = { shell.navigation.goToTab(TopLevelDestination.Search) },
            )
            CodexOfflineRemovalUndo(Modifier.align(Alignment.BottomCenter))
        } else {
            CodexDetailDestination(shell, codexId, entry.codexTagSheetRequest())
        }
    }
}

@Composable
private fun CodexDetailDestination(shell: TheoriaShell, codexId: String, tagSheet: CodexTagSheetRequest) {
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
        features = shell.features,
        durationStates = durationStates,
    ) { state ->
        SideEffect {
            durationOwner.synchronize(
                identity = codexId,
                posts = state.posts,
                resolveInBackground = state.resolveUnknownAnimatedDurations,
            )
        }
        CodexDetailContent(shell, codexId, sortMode, fabContext, fabRestoreState, state, durationOwner, durationStates, tagSheet)
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
    durationStates: MediaDurationStates,
    tagSheet: CodexTagSheetRequest,
) {
    val routes = shell.routes
    val commands = shell.owners.codexCommands
    val virtualUi = virtualCodexUi(
        state, fabRestoreState, { shell.feedFabRestoreRegistry.update(fabContext, it) },
        { shell.navController.popBackStack() }, { shell.overlays.showFollowedCreators = true },
        { shell.overlays.showSavedSearchManagement = true }, shell.savedSearchActions,
    )
    val liveOwner = state.followedOwner ?: state.savedSearchOwner
    CodexDetailScreen(
        codexId = codexId,
        codexName = state.codex?.name,
        headerActions = CodexHeaderActions(
            onRename = { name -> commands.rename(codexId, name, CodexUndoAnchors.detail(codexId)) },
            onOpenActions = { shell.overlays.codexActionTargetId = codexId },
            onTopUpOffline = { shell.transfers.offline.makeAvailableOffline(codexId) },
        ),
        customHeader = markingNewPostsSeen(shell.data, codexId, state.loading, virtualUi?.header),
        likedPostIds = state.likedPostIds,
        newPostIds = state.newPostIds,
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
        pixivUgoiraClient = shell.sources.pixivUgoiraClient,
        durations = durationOwner.feedDurations(durationStates, state.resolveUnknownAnimatedDurations),
        resolvePostById = { postId -> shell.viewerRouteWorkflow.resolvePost(postId, ViewerStreamSource.CODEX) },
        onOpenViewer = { posts, index -> shell.openCodexViewer(codexId, posts, index) },
        onRemovePosts = { posts -> commands.remove(codexId, state.items, posts) },
        onAddPostsToAnotherCodex = { posts -> shell.pendingSave.request(posts, excludedCodexId = codexId) },
        onSavePostToDevice = { post ->
            shell.transfers.downloads.submitPosts(listOf(post), originLabel = "Codex · ${state.codex?.name ?: "Collection"}")
            shell.transfers.showDownloads = true
        },
        onGoToSearch = { shell.navigation.goToTab(TopLevelDestination.Search) },
        onBack = { shell.navController.popBackStack() },
        fabRestoreState = fabRestoreState,
        onFabRestoreStateChange = { updated -> shell.feedFabRestoreRegistry.update(fabContext, updated) },
        onSearchSites = shell::searchFromCodex,
        tagSheetRequested = tagSheet.requested,
        onTagSheetRequestHandled = tagSheet.onHandled,
    )
}

@Composable
private fun CreatorDestination(shell: TheoriaShell) {
    val routes = shell.routes
    val entryCreator by rememberSaveable(stateSaver = CreatorProfileStateSaver) { mutableStateOf(shell.pendingCreatorProfile) }
    BrowsingDestinationStateBoundary(shell.data, shell.sources) { state ->
        val creator = entryCreator ?: return@BrowsingDestinationStateBoundary
        val creatorIdentity = creator.profileId ?: creator.uploadsQuery ?: creator.displayName
        val fabContext = creatorFeedFabContext(creator.source.id, creatorIdentity)
        val fabRestoreState = shell.feedFabRestoreRegistry.state(fabContext) ?: return@BrowsingDestinationStateBoundary
        CreatorRoute(
            coordinator = shell.features.creatorProfile,
            relatedPostsLoader = shell.features.relatedPosts,
            mediaDurationCoordinator = shell.features.mediaDurationCoordinator,
            pixivUgoiraClient = shell.sources.pixivUgoiraClient,
            config = CreatorRouteConfig(
                marks = state.feedMarks(shell.pendingLikePostIds[state.activeProfile.profileId].orEmpty()),
                followed = state.settings.followedCreators.any { it.creator.followKey() == creator.followKey() },
                activeCreator = creator,
                availableSources = state.availableSources,
                resolveUnknownAnimatedDurations = state.settings.contentFilters.resolveUnknownAnimatedDurations,
            ),
            fabRestoreState = fabRestoreState,
            onFabRestoreStateChange = { updated -> shell.feedFabRestoreRegistry.update(fabContext, updated) },
            callbacks = CreatorRouteCallbacks(
                onToggleFollow = shell.owners.follows::toggle,
                onOpenViewer = { effect ->
                    shell.openFeedViewer(
                        ViewerStreamSource.CREATOR_PROFILE,
                        FeedViewerLaunch(effect.posts, effect.context, effect.liveSearchBinding, effect.visibilityFilters),
                        saveContext = false,
                        singleTop = !effect.liveSearchBinding,
                    )
                },
                onNavigateBack = { shell.navController.popBackStack() },
                onToggleLike = { post -> shell.feedLikeAction(ViewerStreamSource.CREATOR_PROFILE)(post) },
                onOpenUrl = { url -> openInBrowser(shell.appContext, url) },
            ),
            onOwnerAvailable = routes.creator::publish,
        )
    }
}

@Composable
private fun ViewerDestination(shell: TheoriaShell, entry: NavBackStackEntry) {
    val routes = shell.routes
    val level = rememberViewerLevel(shell.navController, entry, shell.viewerSessions.session.value?.sessionId)
    if (!level.isFork) ViewerReturnOriginLifetime(entry.id)
    val tagActions = remember(shell) { viewerTagSearchActions(shell) }
    val effectCallbacks = remember(shell, level.isFork, level.feed) { viewerEffectCallbacks(shell, tagActions).forLevel(level) { shell.navController.popBackStack() } }
    val screenCallbacks = remember(shell) { viewerScreenCallbacks(shell) }
    val onOwnerChanged = remember<(ViewerViewModel?) -> Unit>(routes.viewer) {
        var published: ViewerViewModel? = null
        { owner -> if (owner != null) routes.viewer.publish(owner.also { published = it }) else published?.let(routes.viewer::withdraw) }
    }
    DisposableEffect(shell) { onDispose { shell.flushPendingWrites() } }
    val forSeries = rememberSeriesViewerConfig(shell)
    ViewerDestinationStateBoundary(
        data = shell.data,
        sources = shell.sources,
        feedOwners = routes.feeds + listOfNotNull(level.feed),
    ) { state ->
        CompositionLocalProvider(LocalPostTagSearchActions provides tagActions) {
            ViewerRoute(
                dependencies = ViewerRouteDependencies(
                    onCodexUsageTargetChanged = { target ->
                        if (shell.navController.currentDestination?.route == AppRoute.Viewer) {
                            shell.features.appUsageTracker.onCodexTargetChanged(target)
                        }
                    },
                    sessionRetentionOwner = shell.viewerSessions,
                    canonicalizePost = shell.features.offlineMedia::withoutOfflineLocations,
                    postResolver = shell.viewerRouteWorkflow,
                    mediaPrefetcher = viewerMediaPrefetcher(shell.appContext, shell.sources.pixivUgoiraClient) { state.browsing.settings.viewer.videoQuality },
                    mediaDurationCoordinator = shell.features.mediaDurationCoordinator,
                    commentsLoader = shell.features.comments,
                    restoreSession = shell.viewerRouteWorkflow::restoreSession,
                ),
                config = ViewerScreenConfig(
                    playbackMode = state.browsing.settings.viewer.playbackMode,
                    videoQuality = state.browsing.settings.viewer.videoQuality,
                    pixivUgoiraClient = shell.sources.pixivUgoiraClient,
                    invertMultiImageScrollDirection = state.browsing.settings.viewer.invertMultiImageScrollDirection,
                    instantSeek = state.browsing.settings.viewer.instantSeek,
                    orientationLock = state.browsing.settings.viewer.orientationLock,
                    commentOrder = state.browsing.settings.viewer.commentOrder,
                    subtitleSizePercent = state.browsing.settings.viewer.subtitleSizePercent,
                    likedPostIds = state.browsing.likedPostIds,
                    galleryReadingModes = state.browsing.galleryReadingModes,
                    creatorBrowsingSources = state.browsing.creatorBrowsingSources,
                    playbackDiagnosticsEnabled = BuildConfig.BENCHMARK_FIXTURES_ENABLED,
                ).let(forSeries),
                liveSourceState = viewerLiveSourceState(state, shell.pendingLikePostIds[state.browsing.activeProfile.profileId].orEmpty()),
                effectCallbacks = effectCallbacks,
                screenCallbacks = screenCallbacks,
                onOwnerChanged = onOwnerChanged,
                level = level,
                returnEntryId = entry.id,
            )
        }
    }
}

@Composable
private fun rememberSeriesViewerConfig(shell: TheoriaShell): (ViewerScreenConfig) -> ViewerScreenConfig {
    val owner = shell.routes.viewer.current ?: return { it }
    val session = owner.session.collectAsStateWithLifecycle().value
        ?.takeIf { it.context.streamSource == ViewerStreamSource.SERIES }
        ?: return { it }
    LaunchedEffect(owner) {
        val reader = shell.seriesReader ?: return@LaunchedEffect
        if (owner.getCloseable<AutoCloseable>(SERIES_READER_KEY) == null) {
            reader.opened()
            owner.addCloseable(SERIES_READER_KEY, AutoCloseable(reader::closed))
        }
    }
    val reading = session.series as? ViewerSeries.Reading
    val mode by remember(session.context.queryHash, reading != null) {
        shell.seriesReader?.takeIf { reading != null }?.readingMode(session) ?: emptyFlow()
    }.collectAsStateWithLifecycle(initialValue = reading?.mode)
    val modes = mode?.let { chosen -> session.posts.associate { it.id to chosen } }.orEmpty()
    val marks by remember(session.sessionId) { shell.seriesReader?.stripMarks(session) ?: emptyFlow() }
        .collectAsStateWithLifecycle(initialValue = session.stripMarks)
    return { it.copy(galleryReadingModes = modes, stripMarks = marks) }
}

private const val SERIES_READER_KEY = "series-reader"

private fun viewerLiveSourceState(
    state: ViewerDestinationState,
    pendingLikes: Set<com.theoriacodex.domain.model.PostId>,
) = ViewerRouteLiveSourceState(
    feeds = state.feeds.mapValues { (_, feed) -> feed.copy(retainedLikedPostIds = feed.retainedLikedPostIds + pendingLikes) },
    likedPostIds = state.browsing.likedPostIds,
    savedPostIds = state.browsing.savedPostIds,
    watchedPostIds = state.browsing.watchedPostIds,
)

private suspend fun TheoriaShell.viewerDownloadOrigin(): String {
    val launch = routes.viewer.current?.session?.value?.context
    return when (launch?.streamSource) {
        ViewerStreamSource.SEARCH, ViewerStreamSource.TRAIL -> "Search"
        ViewerStreamSource.FOR_YOU -> "For You"
        ViewerStreamSource.CODEX -> {
            val id = launch.queryHash.removePrefix("codex:")
            val name = data.codexRepository.observeCodices().first().firstOrNull { it.codexId == id }?.name
            "Codex · ${name ?: "Collection"}"
        }
        ViewerStreamSource.CREATOR_PROFILE -> "Creator profile"
        ViewerStreamSource.RECENTS -> "History"
        ViewerStreamSource.RELATED -> "Related posts"
        ViewerStreamSource.SERIES -> "Series"
        null -> "Viewer"
    }
}

private fun viewerTagSearchActions(shell: TheoriaShell): PostTagSearchActions {
    fun stage(edit: SearchAction.DraftEdit) = shell.routes.dispatchOrQueue(SearchAction.StageDraftEdit(edit))
    return PostTagSearchActions(
        addInclude = { post, term -> stage(SearchAction.AddPostIncludeTerm(post, term)) },
        addExclude = { post, term -> stage(SearchAction.AddPostExcludeTerm(post, term)) },
        removeInclude = { _, term -> stage(SearchAction.RemoveIncludeTerm(term)) },
        removeExclude = { _, term -> stage(SearchAction.RemoveExcludeTerm(term)) },
        addAlternative = { post, groupIndex, term -> stage(SearchAction.AddPostIncludeAlternative(post, groupIndex, term)) },
        applyDraft = shell.routes.postTagSearchActions.applyDraft,
    )
}

private fun viewerEffectCallbacks(shell: TheoriaShell, tagActions: PostTagSearchActions) = ViewerRouteEffectCallbacks(
    onSavePost = { post, context ->
        shell.requestSaveToCodex(
            post = post,
            fromForYou = context.streamSource == ViewerStreamSource.FOR_YOU,
            searchQuery = shell.routes.viewer.current?.session?.value
                ?.takeIf { it.context.queryHash == context.queryHash }?.saveQuery,
        )
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
    onClipEpisode = { post, clip ->
        shell.transfers.downloads.submitClip(post, clip)
        shell.transfers.showDownloads = true
    },
    onToggleLike = { post -> shell.toggleLikeAndSyncCodex(post) },
    onOpenCreatorProfile = { creator -> shell.openCreatorProfile(creator) },
    onApplyTag = { post, term, excluded ->
        if (excluded) tagActions.addExclude(post, term.toSearchTerm()) else tagActions.addInclude(post, term.toSearchTerm())
    },
    onRecoverMedia = { post, failedMedia, context, stillApplies ->
        shell.viewerRouteWorkflow.recoverMedia(post, failedMedia, context.streamSource, context.queryHash, stillApplies)
    },
    onLoadMore = shell.viewerRouteWorkflow::loadMore,
    onDismiss = {
        shell.scope.launch { shell.features.search.setViewerLaunchContext(null) }
        shell.navController.popBackStack()
    },
    onRestorationUnavailable = {
        shell.features.search.setViewerLaunchContext(null)
        shell.toast("Viewer session expired")
        shell.navController.popBackStack()
    },
)

private fun viewerScreenCallbacks(shell: TheoriaShell) = ViewerScreenCallbacks(
    onDismissRelatedPosts = shell.viewerRouteWorkflow.relatedShelves::dismiss,
    onRetryRelatedPosts = shell.viewerRouteWorkflow.relatedShelves::retry,
    onShowRelatedPosts = shell.viewerRouteWorkflow.relatedShelves::show,
    onRemoveFromCodex = shell.owners.codexCommands::removePost,
    onPlaybackModeSelected = { shell.owners.settings.onAction(SettingsAction.SetViewerPlaybackMode(it)) },
    onOrientationLockSelected = { shell.owners.settings.onAction(SettingsAction.SetViewerOrientationLock(it)) },
    onVideoQualitySelected = { shell.owners.settings.onAction(SettingsAction.SetChoice(SettingChoices.DefaultVideoQuality, it)) },
    onCommentOrderSelected = { shell.owners.settings.onAction(SettingsAction.SetCommentOrder(it)) },
    onSubtitleSizeAdjusted = { shell.owners.settings.onAction(SettingsAction.AdjustSubtitleSize(it)) },
    onGalleryReadingModeSelected = { postId, mode ->
        shell.scope.launch {
            val session = shell.routes.viewer.current?.session?.value
            if (session?.context?.streamSource == ViewerStreamSource.SERIES) shell.seriesReader?.setReadingMode(session, mode)
            else shell.data.galleryReadingModes.set(postId, mode)
        }
    },
    onVisiblePostChanged = { post, viewedMediaNumber, session ->
        shell.data.markViewedNewPost(post, session)
        shell.viewerRouteWorkflow.recordVisiblePost(post, viewedMediaNumber, session)
    },
    onVisibleMediaChanged = { post, viewedMediaNumber, pageOffset, session ->
        shell.viewerRouteWorkflow.recordVisibleMediaProgress(post, viewedMediaNumber, session, pageOffset)
    },
    onPlaybackProgress = { post, progress, session -> shell.viewerRouteWorkflow.recordPlaybackProgress(post, progress, session) },
    onOpenUrl = { url -> openInBrowser(shell.appContext, url) },
    onGoToSearch = {
        shell.scope.launch { shell.features.search.setViewerLaunchContext(null) }
        shell.navigation.goToTab(TopLevelDestination.Search)
    },
    onOpenCreatorFallback = shell::launchOpenCreator,
)

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
