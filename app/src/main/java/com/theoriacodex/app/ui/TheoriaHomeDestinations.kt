package com.theoriacodex.app.ui

import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.theoriacodex.app.codex.CodexListScreen
import com.theoriacodex.app.codex.likesCodexIdForProfile
import com.theoriacodex.app.media.MediaDurationRouteViewModel
import com.theoriacodex.app.recents.RecentsClearTarget
import com.theoriacodex.app.recommend.state.ForYouEffect
import com.theoriacodex.app.search.state.SearchEffect
import com.theoriacodex.app.recents.RecentsScreen
import com.theoriacodex.app.settings.SettingsAction
import com.theoriacodex.app.source.creatorBrowsingSources
import com.theoriacodex.app.ui.components.FOR_YOU_FEED_FAB_CONTEXT
import com.theoriacodex.app.ui.components.SEARCH_FEED_FAB_CONTEXT
import com.theoriacodex.app.ui.components.codexFeedFabContext
import com.theoriacodex.app.ui.routes.BrowsingDestinationState
import com.theoriacodex.app.ui.routes.BrowsingDestinationStateBoundary
import com.theoriacodex.app.ui.routes.CodexDestinationStateBoundary
import com.theoriacodex.app.ui.routes.ForYouRoute
import com.theoriacodex.app.ui.routes.ForYouRouteCallbacks
import com.theoriacodex.app.ui.routes.ForYouRouteConfig
import com.theoriacodex.app.ui.routes.RecentsDestinationState
import com.theoriacodex.app.ui.routes.RecentsDestinationStateBoundary
import com.theoriacodex.app.ui.routes.SearchRoute
import com.theoriacodex.app.ui.routes.SearchRouteCallbacks
import com.theoriacodex.app.ui.routes.SearchRouteConfig
import com.theoriacodex.app.ui.routes.SettingsDestinationStateBoundary
import com.theoriacodex.app.viewer.ViewerRelatedBinding
import com.theoriacodex.app.viewer.ViewerSession
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.data.repository.RecentPostEntry
import com.theoriacodex.data.repository.RecentPostSection
import com.theoriacodex.data.repository.RecentSearchKind
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/** The swipeable top-level tabs. */
@Composable
internal fun HomePager(shell: TheoriaShell) {
    val pagerState = shell.navigation.pagerState
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        flingBehavior = topLevelPagerFling(pagerState),
    ) { page ->
        when (TopLevelDestination.entries[page]) {
            TopLevelDestination.Search -> SearchDestination(shell)
            TopLevelDestination.ForYou -> ForYouDestination(shell)
            TopLevelDestination.Recents -> RecentsDestination(shell)
            TopLevelDestination.Codex -> CodexLibraryDestination(shell, actionCodexId = null)
            TopLevelDestination.Settings -> {
                SettingsDestinationStateBoundary(shell.owners.settings, shell.routes.searchOwner, libraryStorageSummary(shell.transfers)) {
                    LibraryTransferSettings(shell.transfers)
                }
            }
        }
    }
}

@Composable
private fun SearchDestination(shell: TheoriaShell) {
    val routes = shell.routes
    BrowsingDestinationStateBoundary(shell.data, shell.sources) { state ->
        val fabRestoreState = shell.feedFabRestoreRegistry.state(SEARCH_FEED_FAB_CONTEXT) ?: return@BrowsingDestinationStateBoundary
        SearchRoute(
            coordinator = shell.features.search,
            relatedPostsLoader = shell.features.relatedPosts,
            mediaDurationCoordinator = shell.features.mediaDurationCoordinator,
            pixivUgoiraClient = shell.sources.pixivUgoiraClient,
            config = searchRouteConfig(shell, state),
            fabRestoreState = fabRestoreState,
            onFabRestoreStateChange = { updated -> shell.feedFabRestoreRegistry.update(SEARCH_FEED_FAB_CONTEXT, updated) },
            callbacks = SearchRouteCallbacks(
                onOpenSavedSearch = { saved ->
                    if (saved.search.kind == RecentSearchKind.FYP) shell.openHistoricalForYouSearch(saved.search)
                    else shell.openHistoricalSearch(saved.search)
                },
                onOpenSourceAccounts = { shell.owners.settings.onAction(SettingsAction.OpenSourceAccounts) },
                onOpenViewer = { effect -> shell.openSearchViewer(effect) },
                onShowMessage = { message -> Toast.makeText(shell.appContext, message, Toast.LENGTH_SHORT).show() },
                onToggleLike = { post -> shell.searchLikeAction()(post) },
                onOpenCreatorProfile = shell::launchOpenCreator,
                onOpenLegacyCreatorProfile = shell::launchOpenCreator,
                onRequestSaveToCodex = { post ->
                    shell.requestSaveToCodex(post, searchQuery = routes.searchOwner?.currentState()?.query?.applied)
                },
                onSaveToDevice = shell::requestSaveToDevice,
                onPostUrlCopied = { shell.recordPostUrlCopy() },
                onAddFavoriteTag = shell.addFavoriteTag,
                onRemoveFavoriteTag = shell.removeFavoriteTag,
            ),
            onOwnerAvailable = routes::publishSearch,
        )
    }
}

private fun searchRouteConfig(shell: TheoriaShell, state: BrowsingDestinationState) = SearchRouteConfig(
    savedSearches = shell.savedSearches,
    pendingLikePostIds = shell.pendingLikePostIds[state.activeProfile.profileId].orEmpty(),
    settings = state.settings,
    availableSources = state.availableSources,
    creatorBrowsingSources = state.creatorBrowsingSources,
    likedPostIds = state.likedPostIds,
    savedPostIds = state.savedPostIds,
    watchedPostIds = state.watchedPostIds,
    favoriteTags = state.favoriteTags,
    resolveUnknownAnimatedDurations = state.settings.contentFilters.resolveUnknownAnimatedDurations,
)

/** Binds the Viewer's like action and related posts to the Search query current at this tap. */
private suspend fun TheoriaShell.openSearchViewer(effect: SearchEffect.OpenViewer) {
    val likeAction = searchLikeAction()
    val current = routes.searchOwner?.currentState()?.query
    viewerSessions.retain(
        viewerRouteWorkflow.prepareResumableSession(ViewerSession(
            posts = effect.posts,
            context = effect.context,
            liveSearchBinding = effect.liveSearchBinding,
            searchVisibilityFilters = effect.visibilityFilters,
            onToggleLike = likeAction,
            relatedBinding = ViewerRelatedBinding(ViewerStreamSource.SEARCH, current?.appliedQueryHash.orEmpty()),
            saveQuery = current?.applied,
        )),
    )
    navController.navigate(AppRoute.Viewer) {
        launchSingleTop = !effect.liveSearchBinding
    }
}

@Composable
private fun ForYouDestination(shell: TheoriaShell) {
    val routes = shell.routes
    val navigation = shell.navigation
    BrowsingDestinationStateBoundary(shell.data, shell.sources) { state ->
        val fabRestoreState = shell.feedFabRestoreRegistry.state(FOR_YOU_FEED_FAB_CONTEXT) ?: return@BrowsingDestinationStateBoundary
        ForYouRoute(
            coordinator = shell.features.forYou,
            relatedPostsLoader = shell.features.relatedPosts,
            mediaDurationCoordinator = shell.features.mediaDurationCoordinator,
            pixivUgoiraClient = shell.sources.pixivUgoiraClient,
            config = ForYouRouteConfig(
                settings = state.settings,
                activeProfileLikesCount = state.activeProfileLikesCount,
                availableSources = state.availableSources,
                creatorBrowsingSources = state.creatorBrowsingSources,
                likedPostIds = state.likedPostIds,
                resolveUnknownAnimatedDurations = state.settings.contentFilters.resolveUnknownAnimatedDurations,
            ),
            fabRestoreState = fabRestoreState,
            onFabRestoreStateChange = { updated -> shell.feedFabRestoreRegistry.update(FOR_YOU_FEED_FAB_CONTEXT, updated) },
            callbacks = ForYouRouteCallbacks(
                onOpenViewer = { effect -> shell.openForYouViewer(effect) },
                onNavigateToSearch = { navigation.showTab(TopLevelDestination.Search) },
                onShowMessage = { message -> Toast.makeText(shell.appContext, message, Toast.LENGTH_SHORT).show() },
                onSeedHidden = { _, _ -> shell.showActionableFeedback("Seed hidden", "Undo") },
                onToggleLike = { post -> shell.forYouLikeAction()(post) },
                onRequestSaveToCodex = { post -> shell.requestSaveToCodex(post, fromForYou = true) },
                onSaveToDevice = shell::requestSaveToDevice,
                onPostUrlCopied = { shell.recordPostUrlCopy() },
                onOpenCreatorProfile = shell::launchOpenCreator,
                onOpenLegacyCreatorProfile = shell::launchOpenCreator,
                onAddIncludeTerm = routes.addSearchIncludeTerm,
                onAddExcludeTerm = routes.addSearchExcludeTerm,
                onRemoveIncludeTerm = routes.removeSearchIncludeTerm,
                onRemoveExcludeTerm = routes.removeSearchExcludeTerm,
                onFavoriteTagLongPress = shell.addFavoriteTag,
                onGoToSearch = {
                    navigation.homeTabRoute = TopLevelDestination.Search.route
                    shell.scope.launch { navigation.showTab(TopLevelDestination.Search) }
                },
            ),
            onOwnerAvailable = routes::publishForYou,
        )
    }
}

/** Binds the Viewer's like action and related posts to the For You seed current at this tap. */
private suspend fun TheoriaShell.openForYouViewer(effect: ForYouEffect.OpenViewer) {
    val likeAction = forYouLikeAction()
    viewerSessions.retain(
        viewerRouteWorkflow.prepareResumableSession(ViewerSession(
            posts = effect.posts,
            context = effect.context,
            liveSearchBinding = effect.liveSearchBinding,
            searchVisibilityFilters = effect.visibilityFilters,
            onToggleLike = likeAction,
            relatedBinding = ViewerRelatedBinding(
                ViewerStreamSource.FOR_YOU, "for_you:${routes.forYouBinding.current?.state?.value?.seedId}",
            ),
        )),
    )
    if (effect.liveSearchBinding) features.search.setViewerLaunchContext(effect.context)
    navController.navigate(AppRoute.Viewer)
}

/** Opens one Recents section in the Viewer, resuming each post unless asked to start over. */
private fun TheoriaShell.openRecentSection(
    entries: List<RecentPostEntry>,
    index: Int,
    section: RecentPostSection,
    startOver: Boolean = false,
) {
    val posts = entries.map { entry -> entry.post }
    if (posts.isEmpty()) return
    val context = ViewerLaunchContext(
        queryHash = "recents:${section.name.lowercase()}",
        startIndex = index.coerceIn(0, posts.lastIndex),
        streamSource = ViewerStreamSource.RECENTS,
        scrollOffsetHint = 0,
        recentsSection = section,
    )
    scope.launch {
        val session = viewerRouteWorkflow.prepareResumableSession(
            ViewerSession(posts = posts, context = context),
            startOver = startOver,
        )
        viewerSessions.retain(session)
        data.uiRestoreRepository.setViewerLaunchContext(context)
        navController.navigate(AppRoute.Viewer)
    }
}

private fun TheoriaShell.clearRecents(target: RecentsClearTarget, state: RecentsDestinationState) {
    scope.launch {
        recentsClearWorkflow.clear(
            target = target,
            watchedPosts = state.watchedPosts,
            codexPosts = state.codexPosts,
            searches = state.searches,
            fypSearches = state.fypSearches,
            showActionableFeedback = ::showActionableFeedback,
        )
    }
}

@Composable
private fun RecentsDestination(shell: TheoriaShell) {
    val routes = shell.routes
    RecentsDestinationStateBoundary(shell.data) { state ->
        val durationOwner = viewModel<MediaDurationRouteViewModel>(
            key = "recents-duration-owner",
            factory = MediaDurationRouteViewModel.factory(shell.features.mediaDurationCoordinator, "recents"),
        )
        SideEffect {
            durationOwner.synchronize(
                identity = "recents",
                posts = (state.watchedPosts + state.codexPosts).map(RecentPostEntry::post),
                resolveInBackground = false,
            )
        }
        RecentsScreen(
            onSaveSearch = shell.owners.savedSearches::save,
            watchedPosts = state.watchedPosts,
            codexPosts = state.codexPosts,
            searches = state.searches,
            fypSearches = state.fypSearches,
            activity = state.activity,
            pixivUgoiraClient = shell.sources.pixivUgoiraClient,
            durationStateForPost = durationOwner::observeState,
            onDurationPostVisibilityChanged = durationOwner::onPostVisibilityChanged,
            onDurationEnvironmentChanged = durationOwner::onEnvironmentChanged,
            onAuthoritativeDurationKnown = durationOwner::publishPlayerDuration,
            likedPostIds = state.likedPostIds,
            creatorBrowsingSources = shell.sources.registry.creatorBrowsingSources(),
            tagVideoCountProvider = shell.features.search::tagVideoCount,
            fetchTagVideoCounts = shell.features.search::fetchTagVideoCounts,
            onToggleLike = { post -> shell.toggleLikeAndSyncCodex(post) },
            onRequestSaveToCodex = { post -> shell.requestSaveToCodex(post) },
            onSaveToDevice = shell::requestSaveToDevice,
            onPostUrlCopied = { shell.recordPostUrlCopy() },
            onOpenCreatorProfile = shell::launchOpenCreator,
            onOpenLegacyCreatorProfile = shell::launchOpenCreator,
            onAddIncludeTerm = routes.addSearchIncludeTerm,
            onAddExcludeTerm = routes.addSearchExcludeTerm,
            onRemoveIncludeTerm = routes.removeSearchIncludeTerm,
            onRemoveExcludeTerm = routes.removeSearchExcludeTerm,
            onFavoriteTagLongPress = shell.addFavoriteTag,
            onGoToSearch = {
                shell.navigation.homeTabRoute = TopLevelDestination.Search.route
                shell.scope.launch { shell.navigation.pagerState.scrollToPage(TopLevelDestination.entries.indexOf(TopLevelDestination.Search)) }
            },
            onOpenWatchedPost = { entries, index -> shell.openRecentSection(entries, index, RecentPostSection.WATCHED) },
            onOpenCodexPost = { entries, index -> shell.openRecentSection(entries, index, RecentPostSection.CODEX) },
            onStartOver = { post, section ->
                val entries = if (section == RecentPostSection.CODEX) state.codexPosts else state.watchedPosts
                val index = entries.indexOfFirst { it.post.id == post.id }
                if (index >= 0) shell.openRecentSection(entries, index, section, startOver = true)
            },
            onOpenSearch = shell::openHistoricalSearch,
            onOpenFypSearch = shell::openHistoricalForYouSearch,
            onClear = { target -> shell.clearRecents(target, state) },
        )
    }
}

/**
 * The Codex library. Called from two places on purpose: the Home tab, and the shell's keyed action
 * overlay above the NavHost. Each call retains its flows under its own ViewModel store owner.
 */
@Composable
internal fun CodexLibraryDestination(shell: TheoriaShell, actionCodexId: String?) {
    val commands = shell.owners.codexCommands
    CodexDestinationStateBoundary(
        data = shell.data,
        sources = shell.sources,
        thumbnailCacheGeneration = shell.thumbnailCacheGeneration,
        savedSortFor = { codexId ->
            shell.feedFabRestoreRegistry.state(codexFeedFabContext(codexId))?.sortMode
                ?.let { saved -> CodexSortMode.entries.firstOrNull { it.name == saved } }
                ?: CodexSortMode.NEWEST_SAVED
        },
    ) { state ->
        CodexListScreen(
            codices = state.visibleCodices,
            itemCounts = state.itemCounts,
            codexCoverCandidates = state.coverCandidates,
            observeActionOptions = state.observeActionOptions,
            onOpenCodex = { codexId -> shell.navController.navigate(AppRoute.codexDetail(codexId)) },
            onImportCodex = { shell.codexImportLauncher.launch(arrayOf("application/json", "text/json", "text/plain")) },
            onDownloadCodex = { codexId ->
                shell.scope.launch(start = CoroutineStart.UNDISPATCHED) { shell.downloadCodex(codexId) }
            },
            onMakeAvailableOffline = shell.transfers.offline?.let { it::makeAvailableOffline },
            onShareCodex = { codexId ->
                shell.scope.launch(start = CoroutineStart.UNDISPATCHED) { shell.shareCodex(codexId) }
            },
            onSearchFromCodex = { _, source, includeTags ->
                shell.scope.launch(start = CoroutineStart.UNDISPATCHED) { shell.searchFromCodex(source, includeTags) }
            },
            onCleanCodex = { targetId, comparisonIds -> commands.clean(state.activeProfile.profileId, targetId, comparisonIds) },
            onCommitReorder = commands::reorder,
            onCreateCodex = { name -> commands.create(state.activeProfile.profileId, name) },
            onRenameCodex = commands::rename,
            onSetAutomaticTag = commands::automaticTag,
            onDeleteCodex = { id -> commands.delete(id, state.activeProfile.profileId) },
            initialActionCodexId = actionCodexId,
            onActionsDismissed = { shell.overlays.codexActionTargetId = null },
            onOpenSavedCollectionActions = shell.overlays::openCollectionActions,
            likesCodexId = likesCodexIdForProfile(state.activeProfile.profileId),
            librarySort = state.librarySort,
            libraryUsage = state.libraryUsage,
            onLibrarySortChange = { sort ->
                shell.scope.launch { shell.data.settingsRepository.updateSettings { it.copy(codexLibrarySort = sort) } }
            },
        )
    }
}
