package com.theoriacodex.app.ui

import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.theoriacodex.app.codex.CodexListScreen
import com.theoriacodex.app.codex.likesCodexIdForProfile
import com.theoriacodex.app.media.MediaDurationRouteViewModel
import com.theoriacodex.app.recents.RecentsClearTarget
import com.theoriacodex.app.recommend.state.ForYouEffect
import com.theoriacodex.app.search.state.SearchEffect
import com.theoriacodex.app.recents.RecentsRelatedViewModel
import com.theoriacodex.app.recents.RecentsScreen
import com.theoriacodex.app.settings.SettingsAction
import com.theoriacodex.app.settings.sites.rememberCustomSitesEntry
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
import com.theoriacodex.app.settings.StatsActions
import com.theoriacodex.app.ui.routes.SettingsDestinationStateBoundary
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.data.repository.RecentPostEntry
import com.theoriacodex.data.repository.RecentPostSection
import com.theoriacodex.data.repository.RecentSearchKind
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
                SettingsDestinationStateBoundary(
                    shell.owners.settings, shell.routes.search.current, libraryStorageSummary(shell.transfers),
                    sites = rememberCustomSitesEntry(shell.container),
                    statsActions = remember(shell) {
                        StatsActions(shell::openStatisticsPost, shell::shareStats, shell::readStatsFile, shell::previewStatisticsPost, shell::downloadStats)
                    },
                    diagnostics = shell.diagnosticsEntry,
                ) {
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
                onSaveSearch = shell.owners.savedSearches::save,
                onManageSavedSearches = { shell.overlays.showSavedSearchManagement = true },
                onOpenSourceAccounts = { shell.owners.settings.onAction(SettingsAction.OpenCustomSites) },
                onOpenViewer = { effect -> shell.openSearchViewer(effect) },
                onShowMessage = { message -> Toast.makeText(shell.appContext, message, Toast.LENGTH_SHORT).show() },
                onToggleLike = { post -> shell.feedLikeAction(ViewerStreamSource.SEARCH)(post) },
                onOpenCreatorProfile = shell::launchOpenCreator,
                onOpenLegacyCreatorProfile = shell::launchOpenCreator,
                onRequestSaveToCodex = { post ->
                    shell.requestSaveToCodex(post, searchQuery = routes.search.current?.state?.value?.query?.applied)
                },
                onSaveToDevice = shell::requestSaveToDevice,
                onAddFavoriteTag = shell.addFavoriteTags,
                onRemoveFavoriteTag = shell.removeFavoriteTags,
                onRenameFavoriteTag = shell.renameFavoriteTags,
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

/** Search keeps its own saved launch context, and a launch that will not follow the feed reuses a Viewer on top. */
private suspend fun TheoriaShell.openSearchViewer(effect: SearchEffect.OpenViewer) {
    val query = routes.search.current?.state?.value?.query?.applied
    openFeedViewer(
        ViewerStreamSource.SEARCH,
        FeedViewerLaunch(effect.posts, effect.context, effect.liveSearchBinding, effect.visibilityFilters),
        saveContext = false,
        singleTop = !effect.liveSearchBinding,
        // A typed ID was never searched, so saving it to a Codex has no search to record.
        saveQuery = query?.takeUnless { effect.fromTypedId },
    )
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
                onOpenSourceAccounts = { shell.owners.settings.onAction(SettingsAction.OpenCustomSites) },
                onShowMessage = { message -> Toast.makeText(shell.appContext, message, Toast.LENGTH_SHORT).show() },
                onSeedHidden = { _, _ -> shell.showActionableFeedback("Seed hidden", "Undo") },
                onToggleLike = { post -> shell.feedLikeAction(ViewerStreamSource.FOR_YOU)(post) },
                onRequestSaveToCodex = { post -> shell.requestSaveToCodex(post, fromForYou = true) },
                onSaveToDevice = shell::requestSaveToDevice,
                onOpenCreatorProfile = shell::launchOpenCreator,
                onOpenLegacyCreatorProfile = shell::launchOpenCreator,
                onAddIncludeTerm = routes.addSearchIncludeTerm,
                onAddExcludeTerm = routes.addSearchExcludeTerm,
                onRemoveIncludeTerm = routes.removeSearchIncludeTerm,
                onRemoveExcludeTerm = routes.removeSearchExcludeTerm,
                onFavoriteTagLongPress = shell.addFavoriteTag,
                onGoToSearch = { navigation.showTab(TopLevelDestination.Search) },
            ),
            onOwnerAvailable = routes::publishForYou,
        )
    }
}

/** For You saves a live launch's context for restore, and always stacks a new Viewer. */
private suspend fun TheoriaShell.openForYouViewer(effect: ForYouEffect.OpenViewer) {
    openFeedViewer(
        ViewerStreamSource.FOR_YOU,
        FeedViewerLaunch(effect.posts, effect.context, effect.liveSearchBinding, effect.visibilityFilters),
        saveContext = effect.liveSearchBinding,
        singleTop = false,
    )
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

/** Resolves durations for every post Recents can show. */
@Composable
private fun rememberRecentsDurationOwner(shell: TheoriaShell, state: RecentsDestinationState): MediaDurationRouteViewModel {
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
    return durationOwner
}

@Composable
private fun RecentsDestination(shell: TheoriaShell) {
    val routes = shell.routes
    RecentsDestinationStateBoundary(shell.data) { state ->
        val durationOwner = rememberRecentsDurationOwner(shell, state)
        val relatedOwner = viewModel<RecentsRelatedViewModel>(
            key = "recents-related-owner",
            factory = RecentsRelatedViewModel.factory(shell.features.relatedPosts),
        )
        RecentsScreen(
            related = rememberRelatedShelfBinding(shell, relatedOwner.state, relatedOwner.related),
            onShownPostsChanged = relatedOwner::showing,
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
            onToggleLike = { post ->
                shell.toggleLikeAndSyncCodex(post) { outcome -> relatedOwner.related.onLikeCommitted(post, outcome) }
            },
            onRequestSaveToCodex = { post -> shell.requestSaveToCodex(post) },
            onSaveToDevice = shell::requestSaveToDevice,
            onOpenCreatorProfile = shell::launchOpenCreator,
            onOpenLegacyCreatorProfile = shell::launchOpenCreator,
            onAddIncludeTerm = routes.addSearchIncludeTerm,
            onAddExcludeTerm = routes.addSearchExcludeTerm,
            onRemoveIncludeTerm = routes.removeSearchIncludeTerm,
            onRemoveExcludeTerm = routes.removeSearchExcludeTerm,
            onFavoriteTagLongPress = shell.addFavoriteTag,
            onGoToSearch = { shell.navigation.showTab(TopLevelDestination.Search) },
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
            onMakeAvailableOffline = shell.transfers.offline::makeAvailableOffline,
            onShareCodex = { codexId ->
                shell.scope.launch(start = CoroutineStart.UNDISPATCHED) { shell.shareCodex(codexId) }
            },
            onSearchFromCodex = { _, source, query -> shell.searchFromCodex(source, query) },
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
