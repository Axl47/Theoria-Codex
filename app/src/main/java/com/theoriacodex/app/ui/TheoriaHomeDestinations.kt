package com.theoriacodex.app.ui

import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.theoriacodex.app.recommend.state.ForYouEffect
import com.theoriacodex.app.search.state.SearchEffect
import com.theoriacodex.app.settings.SettingsAction
import com.theoriacodex.app.source.creatorBrowsingSources
import com.theoriacodex.app.ui.components.FOR_YOU_FEED_FAB_CONTEXT
import com.theoriacodex.app.ui.components.SEARCH_FEED_FAB_CONTEXT
import com.theoriacodex.app.ui.routes.BrowsingDestinationState
import com.theoriacodex.app.ui.routes.BrowsingDestinationStateBoundary
import com.theoriacodex.app.ui.routes.ForYouRoute
import com.theoriacodex.app.ui.routes.ForYouRouteCallbacks
import com.theoriacodex.app.ui.routes.ForYouRouteConfig
import com.theoriacodex.app.ui.routes.SearchRoute
import com.theoriacodex.app.ui.routes.SearchRouteCallbacks
import com.theoriacodex.app.ui.routes.SearchRouteConfig
import com.theoriacodex.data.repository.RecentSearchKind
import com.theoriacodex.data.repository.ViewerStreamSource

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
            TopLevelDestination.Library -> LibraryDestination(shell)
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
    marks = state.feedMarks(shell.pendingLikePostIds[state.activeProfile.profileId].orEmpty()),
    settings = state.settings,
    availableSources = state.availableSources,
    creatorBrowsingSources = state.creatorBrowsingSources,
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
