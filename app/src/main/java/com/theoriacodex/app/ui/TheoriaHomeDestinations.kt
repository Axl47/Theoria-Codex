package com.theoriacodex.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import com.theoriacodex.app.series.SeriesTabDestination
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.theoriacodex.app.codex.LocalQuickSave
import com.theoriacodex.app.codex.QuickSave
import com.theoriacodex.app.media.reconnections
import com.theoriacodex.app.recents.recentSearchPresentation
import com.theoriacodex.app.recommend.ForYouDeckWiring
import com.theoriacodex.app.recommend.ForYouLevel
import com.theoriacodex.app.related.relatedViewerContext
import com.theoriacodex.app.search.SearchVisibilityFilters
import com.theoriacodex.app.search.searchExecutionKey
import com.theoriacodex.app.search.state.SearchEffect
import com.theoriacodex.app.search.state.SearchSourceScope
import com.theoriacodex.app.settings.SettingsAction
import com.theoriacodex.app.source.creatorBrowsingSources
import com.theoriacodex.app.ui.components.FOR_YOU_FEED_FAB_CONTEXT
import com.theoriacodex.app.ui.components.SEARCH_FEED_FAB_CONTEXT
import com.theoriacodex.app.ui.routes.BrowsingDestinationState
import com.theoriacodex.app.ui.routes.BrowsingDestinationStateBoundary
import com.theoriacodex.app.ui.routes.ForYouRoute
import com.theoriacodex.app.ui.routes.ForYouRouteCallbacks
import com.theoriacodex.app.ui.routes.ForYouRouteConfig
import com.theoriacodex.app.ui.routes.ForYouRouteServices
import com.theoriacodex.app.ui.routes.SearchRoute
import com.theoriacodex.app.ui.routes.SearchRouteCallbacks
import com.theoriacodex.app.ui.routes.SearchRouteConfig
import com.theoriacodex.app.ui.trails.shellTrailSources
import com.theoriacodex.app.ui.trails.trailSpec
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.data.repository.MAX_SAVED_SEARCH_NAME_LENGTH
import com.theoriacodex.data.repository.RecentPostEntry
import com.theoriacodex.data.repository.RecentPostSection
import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.data.repository.RecentSearchKind
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.data.repository.savedFor
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
internal fun HomePager(shell: TheoriaShell) {
    val pagerState = shell.navigation.pagerState
    val forYouGrid = shell.feedFabRestoreRegistry.state(FOR_YOU_FEED_FAB_CONTEXT)?.grid == true
    ReportsLaunchContent(launchTab = {
        TopLevelDestination.entries[pagerState.settledPage].takeIf { shell.navReady && it.route == shell.navigation.homeTabRoute }
    }) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = tabSwipeEnabled(pagerState.settledPage, forYouGrid),
            flingBehavior = topLevelPagerFling(pagerState),
        ) { page ->
            val destination = TopLevelDestination.entries[page]
            val shown = remember(pagerState, destination) { { tabShown(destination, pagerState.targetPage, pagerState.settledPage) } }
            CompositionLocalProvider(LocalTabShown provides shown) {
                when (destination) {
                    TopLevelDestination.Search -> SearchDestination(shell)
                    TopLevelDestination.ForYou -> ForYouDestination(shell)
                    TopLevelDestination.Library -> LibraryDestination(shell)
                    TopLevelDestination.Series -> SeriesTabDestination(shell)
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
            config = searchRouteConfig(shell, state).copy(omnibox = { rememberSearchOmniboxEntries(shell, state) }),
            fabRestoreState = fabRestoreState,
            onFabRestoreStateChange = { updated -> shell.feedFabRestoreRegistry.update(SEARCH_FEED_FAB_CONTEXT, updated) },
            callbacks = SearchRouteCallbacks(
                onOpenSavedSearch = shell::openSavedSearch,
                onResultsShown = shell.savedSearchOpens::resultsShown,
                onSaveSearch = shell.owners.savedSearches::save,
                onUpdateSavedSearch = { saved, search -> shell.owners.savedSearches.update(saved.id, search) },
                onManageSavedSearches = { shell.overlays.showSavedSearchManagement = true },
                onOpenSourceAccounts = { shell.owners.settings.onAction(SettingsAction.OpenCustomSites) },
                onSetSourceWeights = { sources, weights -> shell.owners.settings.onAction(SettingsAction.SetSourceWeights(sources, weights)) },
                onOpenViewer = { effect -> shell.openSearchViewer(effect) },
                onShowMessage = { message -> Toast.makeText(shell.appContext, message, Toast.LENGTH_SHORT).show() },
                onToggleLike = { post -> shell.feedLikeAction(ViewerStreamSource.SEARCH)(post) },
                onRequestSaveToCodex = { post ->
                    shell.requestSaveToCodex(post, searchQuery = routes.search.current?.state?.value?.query?.applied)
                },
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
    favoriteTags = state.favoriteTags,
    resolveUnknownAnimatedDurations = state.settings.contentFilters.resolveUnknownAnimatedDurations,
)

private suspend fun TheoriaShell.openSearchViewer(effect: SearchEffect.OpenViewer) {
    val query = routes.search.current?.state?.value?.query?.applied
    openFeedViewer(
        ViewerStreamSource.SEARCH,
        FeedViewerLaunch(effect.posts, effect.context, effect.liveSearchBinding, effect.visibilityFilters),
        saveContext = false,
        singleTop = !effect.liveSearchBinding,
        saveQuery = query?.takeUnless { effect.fromTypedId },
    )
}

@Composable
private fun ForYouDestination(shell: TheoriaShell) {
    val quickSave = LocalQuickSave.current
    val writes = rememberTriageHandOff()
    val tabShown = LocalTabShown.current
    val shown by remember(tabShown) { derivedStateOf(tabShown) }
    BrowsingDestinationStateBoundary(shell.data, shell.sources) { state ->
        val fabRestoreState = shell.feedFabRestoreRegistry.state(FOR_YOU_FEED_FAB_CONTEXT) ?: return@BrowsingDestinationStateBoundary
        ForYouRoute(
            services = ForYouRouteServices(
                coordinator = shell.features.forYou,
                wiring = { shell.forYouWiring(quickSave, writes.viewModelScope) },
                mediaDurationCoordinator = shell.features.mediaDurationCoordinator,
                pixivUgoiraClient = shell.sources.pixivUgoiraClient,
                recoverPostMedia = shell.features.search::recoverPostMedia,
            ),
            config = ForYouRouteConfig(
                settings = state.settings,
                availableSources = state.availableSources,
                creatorBrowsingSources = state.creatorBrowsingSources,
                marks = state.feedMarks(shell.pendingLikePostIds[state.activeProfile.profileId].orEmpty()),
                resolveUnknownAnimatedDurations = state.settings.contentFilters.resolveUnknownAnimatedDurations,
                isSearchSaved = { query -> shell.savedSearches.savedFor(trailSearchEntry(query)) != null },
                shown = shown,
            ),
            fabRestoreState = fabRestoreState,
            onFabRestoreStateChange = { updated -> shell.feedFabRestoreRegistry.update(FOR_YOU_FEED_FAB_CONTEXT, updated) },
            callbacks = ForYouRouteCallbacks(
                onOpenPost = shell::openForYouPost,
                onOpenRelated = shell::openForYouRelated,
                onPickCodex = { post, onPick -> shell.pendingSave.request(listOf(post), fromForYou = true, onPick = onPick) },
                onSaveSearch = shell::saveTrailSearch,
                onOpenSourceAccounts = { shell.owners.settings.onAction(SettingsAction.OpenCustomSites) },
                onShowMessage = { message -> Toast.makeText(shell.appContext, message, Toast.LENGTH_SHORT).show() },
                onSelectProfile = { id -> shell.owners.settings.onAction(SettingsAction.SetActiveProfile(id)) },
                onToggleLike = { post -> shell.feedLikeAction(ViewerStreamSource.FOR_YOU)(post) },
                onRequestSaveToCodex = { post -> shell.requestSaveToCodex(post, fromForYou = true) },
                onGoToSearch = { shell.navigation.showTab(TopLevelDestination.Search) },
            ),
            onOwnerAvailable = shell.routes::publishForYou,
        )
    }
}

private fun TheoriaShell.forYouWiring(quickSave: QuickSave, writeScope: CoroutineScope) = ForYouDeckWiring(
    actions = ShellTriageActions(this),
    quickSave = quickSave,
    trails = { request, animatedOnly -> trailSpec(request, shellTrailSources(), SearchVisibilityFilters(animatedOnly = animatedOnly)) },
    relatedLoader = features.relatedPosts,
    writeScope = writeScope,
    reconnections = sources.networkOnline.reconnections(),
    verdicts = data.forYouVerdicts,
    copiesOf = data.fileMirrors::copiesOf,
    pictures = features.pictureTags,
)

private fun TheoriaShell.openForYouPost(level: ForYouLevel, post: PostId, scrollOffsetHint: Int) {
    val posts = level.feed.value.posts
    val index = posts.indexOfFirst { it.id == post }.takeIf { it >= 0 } ?: return
    val context = ViewerLaunchContext(level.id, index, ViewerStreamSource.FOR_YOU, scrollOffsetHint)
    scope.launch {
        openFeedViewer(
            ViewerStreamSource.FOR_YOU,
            FeedViewerLaunch(posts, context, liveSearchBinding = true, visibilityFilters = SearchVisibilityFilters()),
            saveContext = false,
            singleTop = false,
            saveQuery = level.query,
        )
    }
}

private fun TheoriaShell.openForYouRelated(seed: Post, posts: List<Post>, index: Int) {
    scope.launch {
        openFeedViewer(
            ViewerStreamSource.FOR_YOU,
            FeedViewerLaunch(posts, relatedViewerContext(seed, index), liveSearchBinding = false, visibilityFilters = SearchVisibilityFilters()),
            saveContext = false,
            singleTop = false,
        )
    }
}

private fun trailSearchEntry(query: Query) =
    RecentSearchEntry(query, searchExecutionKey(query, SearchSourceScope.fromQuery(query)), System.currentTimeMillis())

private fun TheoriaShell.saveTrailSearch(query: Query) {
    val entry = trailSearchEntry(query)
    owners.savedSearches.save(recentSearchPresentation(entry).title.take(MAX_SAVED_SEARCH_NAME_LENGTH), entry)
}
