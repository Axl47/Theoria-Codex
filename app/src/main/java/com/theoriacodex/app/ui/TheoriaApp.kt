package com.theoriacodex.app.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import com.theoriacodex.app.viewer.LocalViewerReturnTransition
import com.theoriacodex.app.viewer.ViewerReturnTransition
import com.theoriacodex.app.viewer.ViewerReturnLifetime

import com.theoriacodex.app.update.ReleaseChangelogEntry
import com.theoriacodex.app.update.mergeReleaseHistory
import com.theoriacodex.app.update.toReleaseHistoryEntry

import com.theoriacodex.data.repository.followKey
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.theoriacodex.app.codex.CodexSaveViewModel
import com.theoriacodex.app.codex.transfer.readCodexDocument
import com.theoriacodex.app.codex.transfer.writeCodexDocument
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.theoriacodex.app.R
import com.theoriacodex.app.BuildConfig
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.theoriacodex.app.codex.CodexDetailScreen
import com.theoriacodex.app.media.MediaDurationRouteViewModel
import com.theoriacodex.app.codex.CodexListScreen
import com.theoriacodex.app.codex.CodexCommandViewModel
import com.theoriacodex.app.codex.CodexCommandHost
import com.theoriacodex.app.codex.CodexCollectionSource
import com.theoriacodex.app.codex.CodexSaveRelevanceLoader
import com.theoriacodex.app.codex.LocalCodexSaveRelevanceLoader
import com.theoriacodex.app.codex.SaveToCodexSheet
import com.theoriacodex.app.codex.profileScopedCodexId
import com.theoriacodex.app.codex.likesCodexIdForProfile
import com.theoriacodex.app.codex.transfer.CodexExportResult
import com.theoriacodex.app.codex.transfer.CodexImportViewModel
import com.theoriacodex.app.codex.transfer.CodexImportHost
import com.theoriacodex.app.appshell.AppShellAction
import com.theoriacodex.app.appshell.AppShellEffect
import com.theoriacodex.app.appshell.StartupUpdateChoice
import com.theoriacodex.app.appshell.AppShellViewModel
import com.theoriacodex.app.appshell.IncomingUriKind
import com.theoriacodex.app.appshell.ViewerSessionRetentionViewModel
import com.theoriacodex.app.creator.CreatorOpeningWorkflow
import com.theoriacodex.app.di.TheoriaAppContainer
import com.theoriacodex.app.creator.state.CreatorAction
import com.theoriacodex.app.related.retainedLikedPostIds
import com.theoriacodex.app.related.LikeToggleOutcome
import com.theoriacodex.app.recents.RecentsScreen
import com.theoriacodex.app.recents.fypSeedBySource
import com.theoriacodex.app.recommend.state.ForYouAction
import com.theoriacodex.app.recents.RecentsClearWorkflow
import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.search.state.SearchSourceScope
import com.theoriacodex.app.settings.SettingsAction
import com.theoriacodex.app.settings.SettingsEffect
import com.theoriacodex.app.settings.SettingsViewModel
import com.theoriacodex.app.post.PostMutationViewModel
import com.theoriacodex.app.tags.PostTagContextProvider
import com.theoriacodex.app.ui.components.MutationFeedbackHost
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.source.creatorBrowsingSources
import com.theoriacodex.app.source.parseExternalCreatorDeepLink
import com.theoriacodex.app.source.parseExternalPostDeepLink
import com.theoriacodex.app.ui.theme.TheoriaNightTheme
import com.theoriacodex.app.ui.routes.ViewerRoute
import com.theoriacodex.app.ui.routes.ViewerRouteDependencies
import com.theoriacodex.app.ui.routes.ViewerRouteEffectCallbacks
import com.theoriacodex.app.ui.routes.ViewerRouteLiveSourceSnapshot
import com.theoriacodex.app.ui.routes.ViewerRouteLiveSourceState
import com.theoriacodex.app.ui.routes.ViewerRouteOwnerHandle
import com.theoriacodex.app.ui.routes.ViewerRouteRenderConfig
import com.theoriacodex.app.ui.routes.ViewerRouteScreenCallbacks
import com.theoriacodex.app.ui.routes.ViewerRouteWorkflow
import com.theoriacodex.app.ui.routes.shareViewerPostMessage
import com.theoriacodex.app.ui.routes.SearchRoute
import com.theoriacodex.app.ui.routes.SearchRouteCallbacks
import com.theoriacodex.app.ui.routes.SearchRouteConfig
import com.theoriacodex.app.ui.routes.SearchRouteOwnerHandle
import com.theoriacodex.app.ui.routes.ForYouRoute
import com.theoriacodex.app.ui.routes.ForYouRouteCallbacks
import com.theoriacodex.app.ui.routes.ForYouRouteConfig
import com.theoriacodex.app.ui.routes.ForYouRouteOwnerHandle
import com.theoriacodex.app.ui.routes.CreatorRoute
import com.theoriacodex.app.ui.routes.CreatorRouteCallbacks
import com.theoriacodex.app.ui.routes.CreatorRouteConfig
import com.theoriacodex.app.ui.routes.CreatorRouteOwnerHandle
import com.theoriacodex.app.ui.routes.CollectRouteEffects
import com.theoriacodex.app.ui.routes.PendingRouteActions
import com.theoriacodex.app.ui.routes.RouteOwnerHandleBinding
import com.theoriacodex.app.ui.routes.BrowsingDestinationStateBoundary
import com.theoriacodex.app.ui.routes.CodexDestinationStateBoundary
import com.theoriacodex.app.ui.routes.CodexDetailDestinationStateBoundary
import com.theoriacodex.app.ui.routes.CredentialRecoveryOverlay
import com.theoriacodex.app.ui.routes.PixivAuthorizationCallbackEffect
import com.theoriacodex.app.ui.routes.RecentsDestinationStateBoundary
import com.theoriacodex.app.ui.routes.SaveToCodexDestinationStateBoundary
import com.theoriacodex.app.ui.routes.SettingsDestinationStateBoundary
import com.theoriacodex.app.ui.routes.ViewerDestinationStateBoundary
import com.theoriacodex.app.ui.routes.ActiveProfileCoordinationEffect
import com.theoriacodex.app.ui.routes.activeRecommendationProfile
import com.theoriacodex.app.ui.components.FOR_YOU_FEED_FAB_CONTEXT
import com.theoriacodex.app.ui.components.SEARCH_FEED_FAB_CONTEXT
import com.theoriacodex.app.ui.components.codexFeedFabContext
import com.theoriacodex.app.ui.components.creatorFeedFabContext
import com.theoriacodex.app.ui.components.rememberFeedFabRestoreRegistry
import com.theoriacodex.app.update.StartupUpdateState
import com.theoriacodex.app.update.StartupUpdateWorkflowEffect
import com.theoriacodex.app.viewer.ViewerMediaPrefetcher
import com.theoriacodex.app.viewer.ViewerSession
import com.theoriacodex.app.viewer.prefetchViewerMedia
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.data.repository.RecommendationProfile
import com.theoriacodex.data.repository.RecentPostSection
import com.theoriacodex.data.repository.RecentPostEntry
import com.theoriacodex.data.repository.RecentSearchKind
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Renderable app-shell boundary with dependencies supplied by the application owner. */
@Composable
internal fun TheoriaAppContent(
    appContainer: TheoriaAppContainer,
    viewerSessionOwner: ViewerSessionRetentionViewModel,
    appShellOwner: AppShellViewModel,
    incomingUri: Uri? = null,
    onIncomingUriConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    val appContext = LocalContext.current.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val dataDependencies = appContainer.data
    val feedFabRestoreRegistry = rememberFeedFabRestoreRegistry(dataDependencies.uiRestoreRepository)
    LaunchedEffect(feedFabRestoreRegistry) { feedFabRestoreRegistry.load() }
    fun recordPostUrlCopy() {
        scope.launch {
            runCatchingPreservingCancellation {
                dataDependencies.statisticsRepository.recordPostUrlCopy()
            }
        }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val libraryTransfers = rememberLibraryTransferTools(appContainer) { snackbarHostState.showSnackbar(it) }
    MutationFeedbackHost(libraryTransfers.downloads.feedback, snackbarHostState)
    val savedSearchOwner = viewModel<com.theoriacodex.app.recents.SavedSearchViewModel>(
        key = "saved-searches",
        factory = viewModelFactory { initializer { com.theoriacodex.app.recents.SavedSearchViewModel(dataDependencies.savedSearches) } },
    )
    val savedSearches by savedSearchOwner.savedSearches.collectAsStateWithLifecycle()
    CollectRouteEffects(savedSearchOwner.effects) { snackbarHostState.showSnackbar(it) }
    val recentsClearWorkflow = remember(dataDependencies.recentsRepository) {
        RecentsClearWorkflow(dataDependencies.recentsRepository)
    }
    suspend fun showActionableFeedback(message: String, actionLabel: String): Boolean {
        return snackbarHostState.showSnackbar(
            message = message,
            actionLabel = actionLabel,
            withDismissAction = false,
            duration = SnackbarDuration.Long,
        ) == SnackbarResult.ActionPerformed
    }
    val sourceDependencies = appContainer.sources
    val updateDependencies = appContainer.updates
    val featureDependencies = appContainer.features
    val workflowDependencies = appContainer.workflows
    val codexCommandOwner = viewModel<CodexCommandViewModel>(
        key = "codex-command-owner",
        factory = viewModelFactory {
            initializer { CodexCommandViewModel(dataDependencies.codexRepository,
                workflowDependencies.likesCodexSync::clearProfile, workflowDependencies.codexTransfer::reorderVisibleCodices,
                workflowDependencies.codexCleaning) }
        },
    )
    val postMutationOwner = viewModel<PostMutationViewModel>(
        key = "post-mutations",
        factory = viewModelFactory { initializer {
            PostMutationViewModel(dataDependencies.settingsRepository, workflowDependencies.likesCodexSync::toggle)
        } },
    )
    val pendingLikePostIds by postMutationOwner.pendingLikePostIds.collectAsStateWithLifecycle()
    MutationFeedbackHost(postMutationOwner.feedback, snackbarHostState)
    MutationFeedbackHost(feedFabRestoreRegistry.feedback, snackbarHostState)
    val codexSaveOwner = viewModel<CodexSaveViewModel>(
        key = "codex-save-owner",
        factory = viewModelFactory {
            initializer {
                CodexSaveViewModel(
                    workflowDependencies.codexTransfer,
                    dataDependencies.codexRepository,
                    dataDependencies.statisticsRepository,
                )
            }
        },
    )
    LaunchedEffect(codexSaveOwner) {
        codexSaveOwner.effects.collect { message -> snackbarHostState.showSnackbar(message) }
    }
    val appUsageTracker = featureDependencies.appUsageTracker
    DisposableEffect(appUsageTracker) {
        val processLifecycle = ProcessLifecycleOwner.get().lifecycle
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> appUsageTracker.onForeground()
                Lifecycle.Event.ON_STOP -> appUsageTracker.onBackground()
                else -> Unit
            }
        }
        processLifecycle.addObserver(observer)
        if (processLifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            appUsageTracker.onForeground()
        }
        onDispose { processLifecycle.removeObserver(observer) }
    }
    val appShellState by appShellOwner.state.collectAsStateWithLifecycle()
    val settingsOwner = viewModel<SettingsViewModel>(
        key = "settings-route-owner",
        factory = SettingsViewModel.factory(appContainer),
    )

    val followsOwner = viewModel<com.theoriacodex.app.creator.CreatorFollowsViewModel>(
        key = "creator-follows",
        factory = com.theoriacodex.app.creator.CreatorFollowsViewModel.factory(
            dataDependencies.settingsRepository, sourceDependencies.registry),
    )
    var showFollowedCreators by remember { mutableStateOf(false) }

    var searchRouteOwner by remember { mutableStateOf<SearchRouteOwnerHandle?>(null) }
    var forYouRouteOwner by remember { mutableStateOf<ForYouRouteOwnerHandle?>(null) }
    var creatorRouteOwner by remember { mutableStateOf<CreatorRouteOwnerHandle?>(null) }
    // These bindings outlive individual destination compositions. Viewer can keep paging through a
    // still-live back-stack owner; only ViewModel lease closure removes the matching handle.
    val searchRouteOwnerBinding = remember {
        RouteOwnerHandleBinding<SearchRouteOwnerHandle> { owner -> searchRouteOwner = owner }
    }
    val forYouRouteOwnerBinding = remember {
        RouteOwnerHandleBinding<ForYouRouteOwnerHandle> { owner -> forYouRouteOwner = owner }
    }
    val creatorRouteOwnerBinding = remember {
        RouteOwnerHandleBinding<CreatorRouteOwnerHandle> { owner -> creatorRouteOwner = owner }
    }
    DisposableEffect(searchRouteOwnerBinding, forYouRouteOwnerBinding, creatorRouteOwnerBinding) {
        onDispose {
            searchRouteOwnerBinding.close()
            forYouRouteOwnerBinding.close()
            creatorRouteOwnerBinding.close()
        }
    }
    var pendingCreatorProfile by rememberSaveable(
        stateSaver = com.theoriacodex.app.ui.routes.CreatorProfileStateSaver,
    ) { mutableStateOf<CreatorProfile?>(null) }
    val pendingSearchActions = remember { PendingRouteActions<SearchAction>() }
    val pendingForYouActions = remember { PendingRouteActions<ForYouAction>() }
    val viewerRouteWorkflow = remember(dataDependencies, sourceDependencies) {
        ViewerRouteWorkflow(
            data = dataDependencies,
            cacheScope = scope,
            sources = sourceDependencies,
            searchOwner = { searchRouteOwner },
            forYouOwner = { forYouRouteOwner },
            creatorOwner = { creatorRouteOwner },
            offlineMedia = featureDependencies.offlineMedia,
        )
    }
    var activeViewerOwner by remember { mutableStateOf<ViewerRouteOwnerHandle?>(null) }
    fun dispatchOrQueueSearchAction(action: SearchAction): Boolean {
        return pendingSearchActions.dispatchOrEnqueue(action) { queuedAction ->
            searchRouteOwner?.dispatch(queuedAction) == true
        }
    }
    fun dispatchOrQueueForYouAction(action: ForYouAction): Boolean {
        return pendingForYouActions.dispatchOrEnqueue(action) { queuedAction ->
            forYouRouteOwner?.dispatch(queuedAction) == true
        }
    }
    fun addSearchIncludeTerm(post: Post, term: SearchTerm): Boolean {
        return dispatchOrQueueSearchAction(SearchAction.AddPostIncludeTerm(post, term))
    }
    fun addSearchExcludeTerm(post: Post, term: SearchTerm): Boolean {
        return dispatchOrQueueSearchAction(SearchAction.AddPostExcludeTerm(post, term))
    }
    fun removeSearchIncludeTerm(term: SearchTerm) {
        dispatchOrQueueSearchAction(SearchAction.RemoveIncludeTerm(term))
    }
    fun removeSearchExcludeTerm(term: SearchTerm) {
        dispatchOrQueueSearchAction(SearchAction.RemoveExcludeTerm(term))
    }
    var showSaveSheet by remember { mutableStateOf(false) }
    var pendingSavePosts by remember { mutableStateOf<List<Post>>(emptyList()) }
    var pendingSaveQuery by remember { mutableStateOf<Query?>(null) }
    var pendingSaveFromForYou by remember { mutableStateOf(false) }
    var excludedCodexIdsForSave by remember { mutableStateOf<Set<String>>(emptySet()) }
    fun requestSaveToCodex(
        posts: List<Post>,
        fromForYou: Boolean = false,
        excludedCodexId: String? = null,
        searchQuery: Query? = null,
    ) {
        val uniquePosts = posts.distinctBy(Post::id)
        if (uniquePosts.isEmpty()) return
        pendingSavePosts = uniquePosts
        pendingSaveQuery = searchQuery
        pendingSaveFromForYou = fromForYou
        excludedCodexIdsForSave = setOfNotNull(excludedCodexId)
        showSaveSheet = true
    }
    fun requestSaveToCodex(post: Post, fromForYou: Boolean = false, searchQuery: Query? = null) {
        requestSaveToCodex(listOf(post), fromForYou, searchQuery = searchQuery)
    }
    fun clearPendingCodexSave() {
        showSaveSheet = false
        pendingSavePosts = emptyList()
        pendingSaveQuery = null
        pendingSaveFromForYou = false
        excludedCodexIdsForSave = emptySet()
    }
    var homeTabRoute by rememberSaveable { mutableStateOf(TopLevelDestination.Search.route) }
    CodexCommandHost(codexCommandOwner, snackbarHostState) { deletedId ->
        if (navController.currentBackStackEntry?.arguments?.getString("codexId") == deletedId) {
            homeTabRoute = TopLevelDestination.Codex.route
            navController.popBackStack(AppRoute.Home, inclusive = false)
        }
    }
    LaunchedEffect(homeTabRoute) {
        if (homeTabRoute == TopLevelDestination.Settings.route) {
            settingsOwner.onAction(SettingsAction.SettingsEntered)
        }
    }
    var pendingTopLevelRoute by remember { mutableStateOf<String?>(null) }
    fun goToSearch() {
        homeTabRoute = TopLevelDestination.Search.route
        pendingTopLevelRoute = TopLevelDestination.Search.route
        if (navController.currentDestination?.route != AppRoute.Home &&
            !navController.popBackStack(AppRoute.Home, inclusive = false)
        ) {
            navController.navigate(AppRoute.Home) {
                popUpTo(navController.graph.findStartDestination().id)
                launchSingleTop = true
            }
        }
    }
    var homeTabRestoreComplete by remember { mutableStateOf(false) }
    var navReady by remember(appContainer) { mutableStateOf(appShellState.appReady) }
    val startupWorkflowState = appShellState.startup
    val startupUpdateState = startupWorkflowState.updateState
    val startupStatusMessage = startupWorkflowState.statusMessage
    val updateChoiceRemote = startupWorkflowState.choice
    val startupUpdateReleaseHistory = appShellState.startupReleaseHistory
    val postInstallChangelog = appShellState.postInstallChangelog
    val postInstallReleaseHistory = appShellState.postInstallReleaseHistory
    val latestInstalledChangelog = appShellState.latestInstalledChangelog
    var releaseHistoryEntries by remember { mutableStateOf<List<ReleaseChangelogEntry>?>(null) }
    val startupActionLocked = startupWorkflowState.actionLocked
    val awaitingInstallerReturn = startupWorkflowState.awaitingInstallerReturn
    var thumbnailCacheGeneration by rememberSaveable { mutableStateOf(0) }
    val addFavoriteTag: (SourceKey, String) -> Unit = remember(postMutationOwner, settingsOwner) {
        { source, tag -> postMutationOwner.favorite(settingsOwner.state.value.activeProfile.profileId, source, tag, true) }
    }
    val removeFavoriteTag: (SourceKey, String) -> Unit = remember(postMutationOwner, settingsOwner) {
        { source, tag -> postMutationOwner.favorite(settingsOwner.state.value.activeProfile.profileId, source, tag, false) }
    }

    fun requestSaveToDevice(post: Post) {
        val origin = if (navController.currentBackStackEntry?.destination?.route == AppRoute.CreatorProfile) {
            "Creator profile"
        } else when (homeTabRoute) {
            TopLevelDestination.ForYou.route -> "FYP"
            TopLevelDestination.Recents.route -> "Recents"
            else -> "Search"
        }
        libraryTransfers.downloads.submitPosts(listOf(post), originLabel = origin)
        libraryTransfers.showDownloads = true
    }

    suspend fun openCreatorProfile(creator: CreatorProfile) {
        pendingCreatorProfile = creator
        if (navController.currentBackStackEntry?.destination?.route == AppRoute.Viewer) {
            activeViewerOwner?.clearSession()
            viewerSessionOwner.clear()
            featureDependencies.search.setViewerLaunchContext(null)
            navController.popBackStack(AppRoute.Viewer, inclusive = true)
        }
        navController.navigate(AppRoute.CreatorProfile) {
            launchSingleTop = true
        }
    }

    val creatorOpening = remember(sourceDependencies.registry, sourceDependencies.httpClient) {
        CreatorOpeningWorkflow(sourceDependencies.registry, sourceDependencies.httpClient)
    }

    suspend fun openCreatorProfile(post: Post) {
        val outcome = creatorOpening.fromPost(post, sourceDependencies.availableSources.value)
        outcome.resolvedPost?.let { dispatchOrQueueSearchAction(SearchAction.RememberResolvedPost(it)) }
        val creator = outcome.creator
        if (creator == null) {
            Toast.makeText(appContext, "Creator profile unavailable", Toast.LENGTH_SHORT).show()
            return
        }
        openCreatorProfile(creator)
        val resolvedPost = outcome.resolvedPost ?: post
        viewerSessionOwner.update { session ->
            val index = session.posts.indexOfFirst { current -> current.id == resolvedPost.id }
            if (index < 0) session else session.copy(
                posts = session.posts.toMutableList().apply { this[index] = resolvedPost },
            )
        }
    }

    suspend fun ensureLikesCodexId(profile: RecommendationProfile): String {
        return workflowDependencies.likesCodexSync.ensureProfileCodex(profile)
    }

    fun toggleLikeAndSyncCodex(post: Post, onCommitted: (LikeToggleOutcome) -> Unit = {}) {
        val profile = settingsOwner.state.value.activeProfile
        postMutationOwner.like(profile, post) { outcome ->
            if (settingsOwner.state.value.activeProfile.profileId == profile.profileId) onCommitted(outcome)
        }
    }

    fun searchLikeAction(): (Post) -> Unit {
        val owner = searchRouteOwnerBinding.current
        val query = owner?.state?.value?.query?.appliedQueryHash
        return { post ->
            toggleLikeAndSyncCodex(post) { outcome ->
                if (owner?.state?.value?.query?.appliedQueryHash == query)
                    owner?.dispatch(SearchAction.RelatedLikeCommitted(post, outcome))
            }
        }
    }

    fun forYouLikeAction(): (Post) -> Unit {
        val owner = forYouRouteOwnerBinding.current
        val seed = owner?.state?.value?.seedId
        return { post ->
            toggleLikeAndSyncCodex(post) { outcome ->
                if (owner?.state?.value?.seedId == seed)
                    owner?.dispatch(ForYouAction.RelatedLikeCommitted(post, outcome))
            }
        }
    }

    fun creatorLikeAction(): (Post) -> Unit {
        val owner = creatorRouteOwnerBinding.current
        val query = owner?.currentState()?.queryHash
        return { post ->
            toggleLikeAndSyncCodex(post) { outcome ->
                if (owner?.currentState()?.queryHash == query)
                    owner?.dispatch(CreatorAction.RelatedLikeCommitted(post, outcome))
            }
        }
    }

    suspend fun searchFromCodex(source: SourceKey, includeTags: List<String>) {
        if (source !in sourceDependencies.registry.availableSources()) {
            Toast.makeText(appContext, "${source.displayName()} source is unavailable", Toast.LENGTH_SHORT).show()
            return
        }

        val normalizedIncludeTags = includeTags
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
        if (normalizedIncludeTags.isEmpty()) {
            Toast.makeText(
                appContext,
                "Select at least one ${source.displayName()} tag",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }

        dispatchOrQueueSearchAction(
            SearchAction.ApplyTagSearch(
                includeTags = normalizedIncludeTags,
                mode = QueryMode.Source(source),
            ),
        )
        goToSearch()
        Toast.makeText(
            appContext,
            "Searching ${source.displayName()} codex tags: ${normalizedIncludeTags.joinToString(separator = ", ")}",
            Toast.LENGTH_SHORT,
        ).show()
    }

    suspend fun downloadCodex(codexId: String) {
        val posts = dataDependencies.codexRepository
            .observeCodexPosts(codexId, CodexSortMode.NEWEST_SAVED)
            .first()
        if (posts.isEmpty()) {
            Toast.makeText(appContext, "Codex has no posts to download", Toast.LENGTH_SHORT).show()
            return
        }

        val name = dataDependencies.codexRepository.observeCodices().first().firstOrNull { it.codexId == codexId }?.name
        libraryTransfers.downloads.submitPosts(posts, "Codex downloads", "Codex · ${name ?: "Collection"}")
        libraryTransfers.showDownloads = true
    }

    suspend fun shareCodex(codexId: String) {
        val export = workflowDependencies.codexTransfer.export(codexId)
        if (export !is CodexExportResult.Success) {
            val message = if (export is CodexExportResult.NotFound) "Codex not found" else "Could not export codex"
            Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
            return
        }
        val payload = export.payload

        val exportsDirectory = dataDependencies.storageDirectory.resolve("exports")
        runCatchingPreservingCancellation {
            val exportFile = writeCodexDocument(exportsDirectory, payload)
            val contentUri = FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                exportFile,
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                putExtra(Intent.EXTRA_SUBJECT, payload.title)
                clipData = ClipData.newRawUri("codex_export", contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(shareIntent, "Share codex").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(chooser)
        }.onFailure {
            Toast.makeText(appContext, "Could not export codex", Toast.LENGTH_SHORT).show()
        }
    }

    val codexImportOwner = viewModel<CodexImportViewModel>(
        key = "codex-import-owner",
        factory = viewModelFactory {
            initializer {
                CodexImportViewModel(
                    transfer = workflowDependencies.codexTransfer,
                    readDocument = { document -> readCodexDocument { appContext.contentResolver.openInputStream(document.toUri()) } },
                    newTargetCodexId = { profileScopedCodexId(dataDependencies.currentActiveRecommendationProfile().profileId) },
                )
            }
        },
    )
    CodexImportHost(codexImportOwner, snackbarHostState)

    val importCodexLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            codexImportOwner.start(uri.toString())
        },
    )

    var codexActionTargetId by remember { mutableStateOf<String?>(null) }
    val codexLibrary: @Composable (String?) -> Unit = { actionId ->
        CodexDestinationStateBoundary(
            data = dataDependencies,
            sources = sourceDependencies,
            thumbnailCacheGeneration = thumbnailCacheGeneration,
        ) { state ->
            CodexListScreen(
                codices = state.visibleCodices,
                itemCounts = state.itemCounts,
                codexCoverCandidates = state.coverCandidates,
                observeActionOptions = state.observeActionOptions,
                onOpenCodex = { codexId ->
                    navController.navigate(AppRoute.codexDetail(codexId))
                },
                onImportCodex = {
                    importCodexLauncher.launch(arrayOf("application/json", "text/json", "text/plain"))
                },
                onDownloadCodex = { codexId ->
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        downloadCodex(codexId)
                    }
                },
                onMakeAvailableOffline = libraryTransfers.offline?.let { it::makeAvailableOffline },
                onShareCodex = { codexId ->
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        shareCodex(codexId)
                    }
                },
                onSearchFromCodex = { _, source, includeTags ->
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        searchFromCodex(source, includeTags)
                    }
                },
                onCleanCodex = { targetId, comparisonIds ->
                    codexCommandOwner.clean(state.activeProfile.profileId, targetId, comparisonIds)
                },
                onCommitReorder = codexCommandOwner::reorder,
                onCreateCodex = { name -> codexCommandOwner.create(state.activeProfile.profileId, name) },
                onRenameCodex = codexCommandOwner::rename,
                onSetAutomaticTag = codexCommandOwner::automaticTag,
                onDeleteCodex = { id -> codexCommandOwner.delete(id, state.activeProfile.profileId) },
                initialActionCodexId = actionId,
                onActionsDismissed = { codexActionTargetId = null },
                likesCodexId = likesCodexIdForProfile(state.activeProfile.profileId),
            )
        }
    }
    codexActionTargetId?.let { id ->
        androidx.compose.runtime.key(id) { codexLibrary(id) }
    }

    suspend fun completeAppStartup() {
        if (navReady) return
        sourceDependencies.accounts.refreshAvailability()
        val currentSettings = dataDependencies.currentSettingsSnapshot()
        ensureLikesCodexId(currentSettings.activeRecommendationProfile())
        settingsOwner.onAction(SettingsAction.RefreshAccounts)
        val legacyLastTabRoute = currentSettings.lastSelectedTabRoute
        homeTabRoute = dataDependencies.uiRestoreRepository.migrateLegacyLastTab(legacyLastTabRoute)
            ?.takeIf { route -> TopLevelDestination.entries.any { destination -> destination.route == route } }
            ?: TopLevelDestination.Search.route
        appShellOwner.onAction(AppShellAction.MarkAppReady)
        navReady = true
    }

    LaunchedEffect(appShellState.readyToEnterApp, navReady) {
        if (appShellState.readyToEnterApp && !navReady) completeAppStartup()
    }

    LaunchedEffect(appShellOwner) {
        appShellOwner.effects.collect { effect ->
            when (effect) {
                is AppShellEffect.Startup -> when (effect.effect) {
                    StartupUpdateWorkflowEffect.ContinueToApp -> Unit
                    StartupUpdateWorkflowEffect.OpenUnknownSourcesSettings -> {
                        openUnknownSourcesSettings(appContext)
                    }
                    is StartupUpdateWorkflowEffect.InstallerOpened -> Unit
                }
            }
        }
    }

    LaunchedEffect(appShellOwner, appContainer) {
        appShellOwner.beginStartup(updateDependencies.startupUpdater, updateDependencies.feedClient,
            installedAppVersionCode(appContext), BuildConfig.UPDATER_ENABLED)
    }

    LaunchedEffect(incomingUri) {
        val uri = incomingUri ?: return@LaunchedEffect
        appShellOwner.onAction(
            AppShellAction.AcceptIncomingUri(
                uri = uri.toString(),
                isPixivAuthorizationCallback = sourceDependencies.pixivAuthController
                    .isAuthorizationCallback(uri),
                isCodexImport = isCodexImportUri(appContext, uri),
            )
        )
        // The AppShell SavedStateHandle now owns the handoff. Clear the Activity's underlying
        // singleTask intent so recreation cannot enqueue the same URI a second time.
        onIncomingUriConsumed()
    }

    val pendingPixivCallback = appShellState.pendingIncomingUri
        ?.takeIf { pending -> pending.kind == IncomingUriKind.PIXIV_AUTH_CALLBACK }
    PixivAuthorizationCallbackEffect(
        pending = pendingPixivCallback,
        sources = sourceDependencies,
        onConsumed = { pending ->
            appShellOwner.onAction(AppShellAction.ConsumeIncomingUri(pending))
        },
        onCompleted = { failure ->
            settingsOwner.onAction(SettingsAction.PixivCallbackCompleted(failure))
        },
    )

    val pendingCodexImport = appShellState.pendingIncomingUri
        ?.takeIf { pending -> pending.kind == IncomingUriKind.CODEX_IMPORT }
    LaunchedEffect(navReady, pendingCodexImport) {
        if (!navReady) return@LaunchedEffect
        val pending = pendingCodexImport ?: return@LaunchedEffect
        val uri = pending.value.toUri()
        fun consumePendingImportUriIfCurrent() {
            appShellOwner.onAction(AppShellAction.ConsumeIncomingUri(pending))
        }
        runCatching {
            appContext.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        codexImportOwner.start(uri.toString())
        consumePendingImportUriIfCurrent()
    }

    val pendingExternalContent = appShellState.pendingIncomingUri
        ?.takeIf { pending -> pending.kind == IncomingUriKind.EXTERNAL_CONTENT }
    LaunchedEffect(navReady, pendingExternalContent) {
        if (!navReady) return@LaunchedEffect
        val pending = pendingExternalContent ?: return@LaunchedEffect
        val uri = pending.value.toUri()
        fun consumePendingUriIfCurrent() {
            appShellOwner.onAction(AppShellAction.ConsumeIncomingUri(pending))
        }

        val deepLink = parseExternalPostDeepLink(uri)
        val creatorDeepLink = if (deepLink == null) parseExternalCreatorDeepLink(uri) else null
        if (deepLink == null && creatorDeepLink == null) {
            Toast.makeText(appContext, "Unsupported URL format", Toast.LENGTH_SHORT).show()
            consumePendingUriIfCurrent()
            return@LaunchedEffect
        }
        if (creatorDeepLink != null) {
            val creator = creatorOpening.fromDeepLink(creatorDeepLink, sourceDependencies.availableSources.value)
            if (creator == null) {
                Toast.makeText(
                    appContext,
                    "Could not open ${creatorDeepLink.sourceLabel} creator URL",
                    Toast.LENGTH_SHORT,
                ).show()
                consumePendingUriIfCurrent()
                return@LaunchedEffect
            }
            openCreatorProfile(creator)
            consumePendingUriIfCurrent()
            return@LaunchedEffect
        }
        val requiredDeepLink = requireNotNull(deepLink)
        val adapter = sourceDependencies.registry.adapterFor(requiredDeepLink.source)
        if (adapter == null) {
            Toast.makeText(appContext, "${requiredDeepLink.sourceLabel} source is unavailable", Toast.LENGTH_SHORT).show()
            consumePendingUriIfCurrent()
            return@LaunchedEffect
        }

        val resolved = try {
            adapter.resolvePost(PostId(source = requiredDeepLink.source, sourcePostId = requiredDeepLink.postId))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val message = error.message ?: "Could not open ${requiredDeepLink.sourceLabel} URL"
            Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
            consumePendingUriIfCurrent()
            return@LaunchedEffect
        }

        if (resolved == null) {
            Toast.makeText(appContext, "${requiredDeepLink.sourceLabel} post was not found", Toast.LENGTH_SHORT).show()
            consumePendingUriIfCurrent()
            return@LaunchedEffect
        }

        val context = ViewerLaunchContext(
            queryHash = "${requiredDeepLink.source.name.lowercase()}-deeplink:${requiredDeepLink.postId}",
            startIndex = 0,
            streamSource = ViewerStreamSource.SEARCH,
            scrollOffsetHint = 0,
        )
        viewerSessionOwner.retain(
            ViewerSession(
                posts = listOf(resolved),
                context = context,
                liveSearchBinding = false,
                initialMediaIndex = requiredDeepLink.initialMediaIndex,
            ),
        )
        dataDependencies.uiRestoreRepository.setViewerLaunchContext(context)
        navController.navigate(AppRoute.Viewer) {
            launchSingleTop = true
        }
        consumePendingUriIfCurrent()
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val currentRoute = currentDestination?.route
    val statisticsCodexId = backStackEntry?.arguments?.getString("codexId")
    LaunchedEffect(appUsageTracker, currentRoute, homeTabRoute, statisticsCodexId) {
        appUsageTracker.onCategoryChanged(statisticsUsageCategory(currentRoute, homeTabRoute))
        if (currentRoute != AppRoute.Viewer) {
            appUsageTracker.onCodexTargetChanged(statisticsCodexId?.takeIf { currentRoute == AppRoute.CodexDetail }
                ?.let { com.theoriacodex.data.repository.CodexUsageTarget(it) })
        }
    }
    val showBottomBar = currentRoute == AppRoute.Home
    val currentContext = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val windowHeightDp = LocalWindowInfo.current.containerSize.height / density.density
    val bottomBarWindowInsets = NavigationBarDefaults.windowInsets
    val bottomSystemInsetDp = with(density) {
        bottomBarWindowInsets.getBottom(this).toDp().value
    }
    val installedVersionCode = remember(appContext) { installedAppVersionCode(appContext) }
    val hostActivity = remember(currentContext) { currentContext.findActivity() }
    val topLevelPagerState = rememberPagerState(
        pageCount = { TopLevelDestination.entries.size },
    )
    val selectedTopLevelIndex = topLevelPagerState.currentPage
        .coerceIn(0, TopLevelDestination.entries.lastIndex)
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(topLevelPagerState.isScrollInProgress) {
        if (topLevelPagerState.isScrollInProgress) {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
    }
    var persistedHomeTabRoute by rememberSaveable { mutableStateOf<String?>(null) }
    val bottomNavigationSizing = remember(windowHeightDp, bottomSystemInsetDp) {
        calculateBottomNavigationSizing(
            windowHeightDp = windowHeightDp,
            bottomSystemInsetDp = bottomSystemInsetDp,
        )
    }
    val bottomBarHeight = bottomNavigationSizing.totalHeightDp.dp
    val bottomBarIconSize = bottomNavigationSizing.iconSizeDp.dp

    if (showFollowedCreators) {
        com.theoriacodex.app.creator.FollowedCreatorsSheet(
            owner = followsOwner,
            onDismiss = { showFollowedCreators = false },
            onOpen = { creator -> showFollowedCreators = false; scope.launch { openCreatorProfile(creator) } },
        )
    }
    CollectRouteEffects(followsOwner.messages) { message ->
        Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
    }
    CollectRouteEffects(settingsOwner.effects) { effect ->
        when (effect) {
            is SettingsEffect.OpenExternalUri -> openInBrowser(appContext, effect.uri)
            is SettingsEffect.ShowMessage -> Toast.makeText(
                appContext,
                effect.message,
                if (effect.long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
            ).show()
            SettingsEffect.LoadChangelog -> {
                try {
                    val remoteHistory = updateDependencies.feedClient.mainPrereleaseHistory(limit = 50)
                        .getOrElse { emptyList() }
                    val merged = mergeReleaseHistory(
                        remoteHistory = remoteHistory,
                        localCurrent = latestInstalledChangelog,
                    )
                    if (merged.isEmpty()) {
                        Toast.makeText(appContext, "No changelog available yet", Toast.LENGTH_SHORT).show()
                    } else {
                        releaseHistoryEntries = merged
                    }
                } finally {
                    settingsOwner.onAction(SettingsAction.ChangelogRequestFinished)
                }
            }
            SettingsEffect.ThumbnailCacheCleared -> thumbnailCacheGeneration += 1
            SettingsEffect.NavigateToSettings -> {
                pendingTopLevelRoute = TopLevelDestination.Settings.route
                homeTabRoute = TopLevelDestination.Settings.route
                if (navReady && currentRoute != AppRoute.Home) {
                    val returnedHome = navController.popBackStack(AppRoute.Home, inclusive = false)
                    if (!returnedHome) {
                        navController.navigate(AppRoute.Home) { launchSingleTop = true }
                    }
                }
            }
        }
    }

    LaunchedEffect(navReady, currentRoute) {
        if (!navReady || currentRoute != AppRoute.Home) return@LaunchedEffect
        val targetIndex = TopLevelDestination.entries.indexOfFirst { destination ->
            destination.route == homeTabRoute
        }.coerceAtLeast(0)
        if (targetIndex != topLevelPagerState.currentPage) {
            topLevelPagerState.scrollToPage(targetIndex)
        }
        homeTabRestoreComplete = true
    }

    LaunchedEffect(navReady, currentRoute, pendingTopLevelRoute) {
        val targetRoute = pendingTopLevelRoute ?: return@LaunchedEffect
        if (!navReady || currentRoute != AppRoute.Home) return@LaunchedEffect
        val targetIndex = TopLevelDestination.entries.indexOfFirst { destination ->
            destination.route == targetRoute
        }.coerceAtLeast(0)
        if (targetIndex != topLevelPagerState.currentPage) {
            topLevelPagerState.scrollToPage(targetIndex)
        }
        pendingTopLevelRoute = null
    }

    LaunchedEffect(
        navReady,
        currentRoute,
        homeTabRestoreComplete,
        topLevelPagerState.currentPage,
        topLevelPagerState.isScrollInProgress,
    ) {
        if (!navReady || currentRoute != AppRoute.Home) return@LaunchedEffect
        if (!homeTabRestoreComplete) return@LaunchedEffect
        if (topLevelPagerState.isScrollInProgress) return@LaunchedEffect
        val route = TopLevelDestination.entries[selectedTopLevelIndex].route
        if (homeTabRoute != route) {
            homeTabRoute = route
        }
        if (persistedHomeTabRoute != route) {
            dataDependencies.uiRestoreRepository.setLastTab(route)
            persistedHomeTabRoute = route
        }
    }

    TheoriaAppPlatformEffects(
        hostActivity = hostActivity,
        viewerActive = currentRoute == AppRoute.Viewer,
        orientation = configuration.orientation,
    )

    DisposableEffect(lifecycleOwner, appShellOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) appShellOwner.onInstallerReturn(installedAppVersionCode(appContext))
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    ActiveProfileCoordinationEffect(
        settingsRepository = dataDependencies.settingsRepository,
        onProfileChanged = { profile -> ensureLikesCodexId(profile) },
    )

    val saveRelevanceLoader = remember(dataDependencies, scope) {
        val source = CodexCollectionSource(dataDependencies.codexRepository,
            dataDependencies.storageDirectory, dataDependencies.cacheRepository)
        CodexSaveRelevanceLoader(dataDependencies.codexRepository, scope, source::observeSaveRelevance)
    }
    val readingPositions by remember(dataDependencies.readingPositions) {
        dataDependencies.readingPositions.observe()
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val watchedEntries by remember(dataDependencies.recentsRepository) {
        dataDependencies.recentsRepository.observeWatchedPosts()
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val watchPresentation = remember(readingPositions, watchedEntries) {
        com.theoriacodex.app.search.postWatchPresentation(readingPositions, watchedEntries)
    }
    val viewerReturnTransition = remember { ViewerReturnTransition() }
    CompositionLocalProvider(
        com.theoriacodex.app.search.LocalPostWatchPresentation provides watchPresentation,
        LocalCodexSaveRelevanceLoader provides saveRelevanceLoader,
        LocalViewerReturnTransition provides viewerReturnTransition,
    ) {
    com.theoriacodex.app.codex.SavedCodexSession {
    PostTagContextProvider(searchRouteOwner?.state, featureDependencies.search::fetchSearchTermCounts) {
        TheoriaNightTheme {
            if (!navReady) {
                val promptRemote = updateChoiceRemote
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.theoria_splash_mark),
                        contentDescription = "Theoria splash",
                        modifier = Modifier.size(180.dp),
                    )
                    if (startupUpdateState is StartupUpdateState.AwaitingUserChoice && promptRemote != null) {
                        StartupUpdatePromptCard(
                            releases = startupUpdateReleaseHistory.ifEmpty {
                                listOf(promptRemote.toReleaseHistoryEntry())
                            },
                            installedVersionCode = installedVersionCode,
                            actionEnabled = !startupActionLocked,
                            onYes = { appShellOwner.chooseUpdate(StartupUpdateChoice.INSTALL) },
                            onNo = { appShellOwner.chooseUpdate(StartupUpdateChoice.SKIP) },
                            onRemindLater = { appShellOwner.chooseUpdate(StartupUpdateChoice.REMIND_LATER) },
                        )
                    } else {
                        if (startupUpdateState !is StartupUpdateState.Failed) {
                            CircularProgressIndicator()
                        }
                        Text(
                            text = startupStatusMessage,
                            color = Color.White,
                        )
                        if (awaitingInstallerReturn) {
                            TextButton(
                                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                                    contentColor = Color.White,
                                ),
                                onClick = appShellOwner::continueCurrentVersion,
                            ) {
                                Text(
                                    text = "Continue current version",
                                    color = Color.White,
                                )
                            }
                        }
                    }
                }
            } else {
                TheoriaAdaptiveScaffold(
                    navigationVisible = showBottomBar,
                    selectedIndex = selectedTopLevelIndex,
                    bottomBarHeight = bottomBarHeight,
                    bottomBarIconSize = bottomBarIconSize,
                    bottomBarWindowInsets = bottomBarWindowInsets,
                    snackbarHostState = snackbarHostState,
                    onDestinationSelected = { destination ->
                        homeTabRoute = destination.route
                        scope.launch { navigateTopLevelDestination(navController, topLevelPagerState, destination) }
                    },
                ) { contentModifier ->
                    NavHost(
                        navController = navController,
                        startDestination = AppRoute.Home,
                        exitTransition = {
                            if (targetState.destination.route == AppRoute.Viewer) ExitTransition.None
                            else fadeOut(tween(700))
                        },
                        popEnterTransition = {
                            if (initialState.destination.route == AppRoute.Viewer) EnterTransition.None
                            else fadeIn(tween(700))
                        },
                        modifier = contentModifier,
                    ) {
                        composable(AppRoute.Home) {
                            HorizontalPager(
                                state = topLevelPagerState,
                                modifier = Modifier.fillMaxSize(),
                            ) { page ->
                                when (TopLevelDestination.entries[page]) {
                                    TopLevelDestination.Search -> {
                                        BrowsingDestinationStateBoundary(dataDependencies, sourceDependencies) { state ->
                                            val fabRestoreState = feedFabRestoreRegistry
                                                .state(SEARCH_FEED_FAB_CONTEXT)
                                                ?: return@BrowsingDestinationStateBoundary
                                            SearchRoute(
                                            coordinator = featureDependencies.search,
                                            relatedPostsLoader = featureDependencies.relatedPosts,
                                            mediaDurationCoordinator = featureDependencies.mediaDurationCoordinator,
                                            pixivUgoiraClient = sourceDependencies.pixivUgoiraClient,
                                            config = SearchRouteConfig(
                                                pendingLikePostIds = pendingLikePostIds[state.activeProfile.profileId].orEmpty(),
                                                settings = state.settings,
                                                availableSources = state.availableSources,
                                                creatorBrowsingSources = state.creatorBrowsingSources,
                                                likedPostIds = state.likedPostIds,
                                                savedPostIds = state.savedPostIds,
                                                watchedPostIds = state.watchedPostIds,
                                                favoriteTags = state.favoriteTags,
                                                resolveUnknownAnimatedDurations =
                                                    state.settings.contentFilters.resolveUnknownAnimatedDurations,
                                            ),
                                            fabRestoreState = fabRestoreState,
                                            onFabRestoreStateChange = { updated ->
                                                feedFabRestoreRegistry.update(SEARCH_FEED_FAB_CONTEXT, updated)
                                            },
                                            callbacks = SearchRouteCallbacks(
                                                onOpenSourceAccounts = { settingsOwner.onAction(SettingsAction.OpenSourceAccounts) },
                                                onOpenViewer = { effect ->
                                                    val likeAction = searchLikeAction()
                                                    val saveQuery = searchRouteOwner?.currentState()?.query?.applied
                                                    viewerSessionOwner.retain(
                                                        viewerRouteWorkflow.prepareResumableSession(ViewerSession(
                                                            posts = effect.posts,
                                                            context = effect.context,
                                                            liveSearchBinding = effect.liveSearchBinding,
                                                            searchVisibilityFilters = effect.visibilityFilters,
                                                            onToggleLike = likeAction,
                                                            saveQuery = saveQuery,
                                                        )),
                                                    )
                                                    navController.navigate(AppRoute.Viewer) {
                                                        launchSingleTop = !effect.liveSearchBinding
                                                    }
                                                },
                                                onShowMessage = { message ->
                                                    Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
                                                },
                                                onToggleLike = { post ->
                                                    searchLikeAction()(post)
                                                },
                                                onOpenCreatorProfile = { creator ->
                                                    scope.launch { openCreatorProfile(creator) }
                                                },
                                                onOpenLegacyCreatorProfile = { post ->
                                                    scope.launch { openCreatorProfile(post) }
                                                },
                                                onRequestSaveToCodex = { post ->
                                                    requestSaveToCodex(post, searchQuery = searchRouteOwner?.currentState()?.query?.applied)
                                                },
                                                onSaveToDevice = ::requestSaveToDevice,
                                                onPostUrlCopied = { recordPostUrlCopy() },
                                                onAddFavoriteTag = addFavoriteTag,
                                                onRemoveFavoriteTag = removeFavoriteTag,
                                            ),
                                            onOwnerAvailable = { owner ->
                                                searchRouteOwnerBinding.publish(owner)
                                                pendingSearchActions.flush(owner::dispatch)
                                            },
                                        )
                                        }
                                    }

                                    TopLevelDestination.ForYou -> {
                                        BrowsingDestinationStateBoundary(dataDependencies, sourceDependencies) { state ->
                                            val fabRestoreState = feedFabRestoreRegistry
                                                .state(FOR_YOU_FEED_FAB_CONTEXT)
                                                ?: return@BrowsingDestinationStateBoundary
                                            ForYouRoute(
                                            coordinator = featureDependencies.forYou,
                                            relatedPostsLoader = featureDependencies.relatedPosts,
                                            mediaDurationCoordinator = featureDependencies.mediaDurationCoordinator,
                                            pixivUgoiraClient = sourceDependencies.pixivUgoiraClient,
                                            config = ForYouRouteConfig(
                                                settings = state.settings,
                                                activeProfileLikesCount = state.activeProfileLikesCount,
                                                availableSources = state.availableSources,
                                                creatorBrowsingSources = state.creatorBrowsingSources,
                                                likedPostIds = state.likedPostIds,
                                                resolveUnknownAnimatedDurations =
                                                    state.settings.contentFilters.resolveUnknownAnimatedDurations,
                                            ),
                                            fabRestoreState = fabRestoreState,
                                            onFabRestoreStateChange = { updated ->
                                                feedFabRestoreRegistry.update(FOR_YOU_FEED_FAB_CONTEXT, updated)
                                            },
                                            callbacks = ForYouRouteCallbacks(
                                                onOpenViewer = { effect ->
                                                    val likeAction = forYouLikeAction()
                                                    viewerSessionOwner.retain(
                                                        viewerRouteWorkflow.prepareResumableSession(ViewerSession(
                                                            posts = effect.posts,
                                                            context = effect.context,
                                                            liveSearchBinding = effect.liveSearchBinding,
                                                            searchVisibilityFilters = effect.visibilityFilters,
                                                            onToggleLike = likeAction,
                                                        )),
                                                    )
                                                    if (effect.liveSearchBinding) {
                                                        featureDependencies.search.setViewerLaunchContext(effect.context)
                                                    }
                                                    navController.navigate(AppRoute.Viewer)
                                                },
                                                onNavigateToSearch = {
                                                    val targetIndex = TopLevelDestination.entries
                                                        .indexOf(TopLevelDestination.Search)
                                                    homeTabRoute = TopLevelDestination.Search.route
                                                    if (topLevelPagerState.currentPage != targetIndex) {
                                                        topLevelPagerState.scrollToPage(targetIndex)
                                                    }
                                                },
                                                onShowMessage = { message ->
                                                    Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
                                                },
                                                onSeedHidden = { _, _ ->
                                                    showActionableFeedback("Seed hidden", "Undo")
                                                },
                                                onToggleLike = { post ->
                                                    forYouLikeAction()(post)
                                                },
                                                onRequestSaveToCodex = { post ->
                                                    requestSaveToCodex(post, fromForYou = true)
                                                },
                                                onSaveToDevice = ::requestSaveToDevice,
                                                onPostUrlCopied = { recordPostUrlCopy() },
                                                onOpenCreatorProfile = { creator ->
                                                    scope.launch { openCreatorProfile(creator) }
                                                },
                                                onOpenLegacyCreatorProfile = { post ->
                                                    scope.launch { openCreatorProfile(post) }
                                                },
                                                onAddIncludeTerm = ::addSearchIncludeTerm,
                                                onAddExcludeTerm = ::addSearchExcludeTerm,
                                                onRemoveIncludeTerm = { _, term -> removeSearchIncludeTerm(term) },
                                                onRemoveExcludeTerm = { _, term -> removeSearchExcludeTerm(term) },
                                                onFavoriteTagLongPress = addFavoriteTag,
                                                onGoToSearch = {
                                                    val targetIndex = TopLevelDestination.entries
                                                        .indexOf(TopLevelDestination.Search)
                                                    homeTabRoute = TopLevelDestination.Search.route
                                                    scope.launch {
                                                        if (topLevelPagerState.currentPage != targetIndex) {
                                                            topLevelPagerState.scrollToPage(targetIndex)
                                                        }
                                                    }
                                                },
                                            ),
                                            onOwnerAvailable = { owner ->
                                                forYouRouteOwnerBinding.publish(owner)
                                                pendingForYouActions.flush(owner::dispatch)
                                            },
                                        )
                                        }
                                    }

                                    TopLevelDestination.Recents -> {
                                        RecentsDestinationStateBoundary(dataDependencies) { state ->
                                            fun openRecentSection(
                                                entries: List<RecentPostEntry>,
                                                index: Int,
                                                section: RecentPostSection,
                                                startOver: Boolean = false,
                                            ) {
                                                val posts = entries.map { entry -> entry.post }
                                                if (posts.isNotEmpty()) {
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
                                                        viewerSessionOwner.retain(session)
                                                        dataDependencies.uiRestoreRepository
                                                            .setViewerLaunchContext(context)
                                                        navController.navigate(AppRoute.Viewer)
                                                    }
                                                }
                                            }
                                            val recentsDurationOwner = viewModel<MediaDurationRouteViewModel>(
                                                key = "recents-duration-owner",
                                                factory = MediaDurationRouteViewModel.factory(
                                                    featureDependencies.mediaDurationCoordinator,
                                                    "recents",
                                                ),
                                            )
                                            SideEffect {
                                                recentsDurationOwner.synchronize(
                                                    identity = "recents",
                                                    posts = (state.watchedPosts + state.codexPosts)
                                                        .map(RecentPostEntry::post),
                                                    resolveInBackground = false,
                                                )
                                            }
                                            RecentsScreen(
                                            savedSearches = savedSearches,
                                            onSaveSearch = savedSearchOwner::save,
                                            onRenameSavedSearch = savedSearchOwner::rename,
                                            onRemoveSavedSearch = savedSearchOwner::remove,
                                            watchedPosts = state.watchedPosts,
                                            codexPosts = state.codexPosts,
                                            searches = state.searches,
                                            fypSearches = state.fypSearches,
                                            activity = state.activity,
                                            pixivUgoiraClient = sourceDependencies.pixivUgoiraClient,
                                            durationStateForPost = recentsDurationOwner::observeState,
                                            onDurationPostVisibilityChanged =
                                                recentsDurationOwner::onPostVisibilityChanged,
                                            onDurationEnvironmentChanged =
                                                recentsDurationOwner::onEnvironmentChanged,
                                            onAuthoritativeDurationKnown =
                                                recentsDurationOwner::publishPlayerDuration,
                                            likedPostIds = state.likedPostIds,
                                            creatorBrowsingSources = sourceDependencies.registry.creatorBrowsingSources(),
                                            tagVideoCountProvider = featureDependencies.search::tagVideoCount,
                                            fetchTagVideoCounts = featureDependencies.search::fetchTagVideoCounts,
                                            onToggleLike = { post -> toggleLikeAndSyncCodex(post) },
                                            onRequestSaveToCodex = { post ->
                                                requestSaveToCodex(post)
                                            },
                                            onSaveToDevice = ::requestSaveToDevice,
                                            onPostUrlCopied = { recordPostUrlCopy() },
                                            onOpenCreatorProfile = { creator ->
                                                scope.launch { openCreatorProfile(creator) }
                                            },
                                            onOpenLegacyCreatorProfile = { post ->
                                                scope.launch { openCreatorProfile(post) }
                                            },
                                            onAddIncludeTerm = ::addSearchIncludeTerm,
                                            onAddExcludeTerm = ::addSearchExcludeTerm,
                                            onRemoveIncludeTerm = { _, term -> removeSearchIncludeTerm(term) },
                                            onRemoveExcludeTerm = { _, term -> removeSearchExcludeTerm(term) },
                                            onFavoriteTagLongPress = addFavoriteTag,
                                            onGoToSearch = {
                                                homeTabRoute = TopLevelDestination.Search.route
                                                val targetIndex = TopLevelDestination.entries
                                                    .indexOf(TopLevelDestination.Search)
                                                scope.launch {
                                                    topLevelPagerState.scrollToPage(targetIndex)
                                                }
                                            },
                                            onOpenWatchedPost = { entries, index ->
                                                openRecentSection(
                                                    entries,
                                                    index,
                                                    RecentPostSection.WATCHED,
                                                )
                                            },
                                            onOpenCodexPost = { entries, index ->
                                                openRecentSection(
                                                    entries,
                                                    index,
                                                    RecentPostSection.CODEX,
                                                )
                                            },
                                            onStartOver = { post, section ->
                                                val entries = if (section == RecentPostSection.CODEX) state.codexPosts else state.watchedPosts
                                                val index = entries.indexOfFirst { it.post.id == post.id }
                                                if (index >= 0) openRecentSection(entries, index, section, startOver = true)
                                            },
                                            onOpenSearch = { entry ->
                                                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                                    val historicalScope = when (entry.kind) {
                                                        RecentSearchKind.MULTI_SEARCH -> {
                                                            SearchSourceScope.fromSources(entry.sources)
                                                        }
                                                        RecentSearchKind.SOURCE,
                                                        RecentSearchKind.UNIFIED,
                                                        RecentSearchKind.FYP,
                                                        -> SearchSourceScope.fromQuery(entry.query)
                                                    }
                                                    dispatchOrQueueSearchAction(
                                                        SearchAction.ApplyHistoricalQuery(
                                                            query = entry.query,
                                                            sourceScope = historicalScope,
                                                        ),
                                                    )
                                                    val targetIndex = TopLevelDestination.entries.indexOf(TopLevelDestination.Search)
                                                    homeTabRoute = TopLevelDestination.Search.route
                                                    pendingTopLevelRoute = TopLevelDestination.Search.route
                                                    navController.navigate(AppRoute.Home) {
                                                        popUpTo(navController.graph.findStartDestination().id) {
                                                            saveState = true
                                                        }
                                                        launchSingleTop = true
                                                        restoreState = true
                                                    }
                                                    if (topLevelPagerState.currentPage != targetIndex) {
                                                        topLevelPagerState.scrollToPage(targetIndex)
                                                    }
                                                }
                                            },
                                            onOpenFypSearch = { entry ->
                                                dispatchOrQueueForYouAction(
                                                    ForYouAction.ReplaySearch(
                                                        seedBySource = entry.fypSeedBySource(),
                                                        sortMode = entry.query.sort,
                                                    ),
                                                )
                                                val targetIndex = TopLevelDestination.entries
                                                    .indexOf(TopLevelDestination.ForYou)
                                                homeTabRoute = TopLevelDestination.ForYou.route
                                                scope.launch {
                                                    if (topLevelPagerState.currentPage != targetIndex) {
                                                        topLevelPagerState.scrollToPage(targetIndex)
                                                    }
                                                }
                                            },
                                            onClear = { target ->
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
                                            },
                                        )
                                        }
                                    }

                                    TopLevelDestination.Codex -> {
                                        codexLibrary(null)
                                    }

                                    TopLevelDestination.Settings -> {
                                        SettingsDestinationStateBoundary(settingsOwner, searchRouteOwner, libraryStorageSummary(libraryTransfers)) {
                                            LibraryTransferSettings(libraryTransfers)
                                        }
                                    }
                                }
                            }
                        }
                        val openCodexViewer: (String, List<Post>, Int) -> Unit = { codexId, visiblePosts, index ->
                            val context = ViewerLaunchContext(
                                queryHash = "codex:$codexId",
                                startIndex = index,
                                streamSource = ViewerStreamSource.CODEX,
                                scrollOffsetHint = 0,
                            )
                            scope.launch {
                                viewerSessionOwner.retain(
                                    viewerRouteWorkflow.prepareResumableSession(ViewerSession(
                                        posts = visiblePosts,
                                        context = context,
                                        liveSearchBinding = false,
                                    )),
                                )
                                dataDependencies.uiRestoreRepository.setViewerLaunchContext(context)
                                navController.navigate(AppRoute.Viewer)
                            }
                        }
                        composable(
                            route = AppRoute.CodexDetail,
                            arguments = listOf(navArgument("codexId") { type = NavType.StringType }),
                        ) { entry ->
                            val codexId = requireNotNull(entry.arguments?.getString("codexId"))
                            LaunchedEffect(entry, codexId, dataDependencies.statisticsRepository) {
                                runCatchingPreservingCancellation {
                                    dataDependencies.statisticsRepository.recordCodexEntry(codexId)
                                }
                            }
                            if (codexId == com.theoriacodex.app.codex.SAVED_CODEX_ID) {
                                com.theoriacodex.app.ui.routes.SavedCodexRoute(
                                    data = dataDependencies,
                                    sources = sourceDependencies,
                                    durations = featureDependencies.mediaDurationCoordinator,
                                    restoreRegistry = feedFabRestoreRegistry,
                                    onBack = { navController.popBackStack() },
                                    onOpenCodex = { navController.navigate(AppRoute.codexDetail(it)) },
                                    onOpenViewer = openCodexViewer,
                                    onOpenCodexActions = { codexActionTargetId = it },
                                    onManageFollowed = { showFollowedCreators = true },
                                    onRenameSearch = savedSearchOwner::rename,
                                    onRemoveSearch = savedSearchOwner::remove,
                                    resolvePost = { viewerRouteWorkflow.resolvePost(it, ViewerStreamSource.CODEX) },
                                )
                                return@composable
                            }
                            val fabContext = codexFeedFabContext(codexId)
                            val fabRestoreState = feedFabRestoreRegistry.state(fabContext)
                                ?: return@composable
                            val sortMode = fabRestoreState.sortMode?.let { encoded ->
                                CodexSortMode.entries.firstOrNull { mode -> mode.name == encoded }
                            } ?: CodexSortMode.NEWEST_SAVED
                            val durationOwner = viewModel<MediaDurationRouteViewModel>(
                                key = "codex-detail-duration-$codexId",
                                factory = MediaDurationRouteViewModel.factory(
                                    featureDependencies.mediaDurationCoordinator,
                                    "codex-detail:$codexId",
                                ),
                            )
                            val durationStates by durationOwner.states.collectAsStateWithLifecycle()
                            CodexDetailDestinationStateBoundary(
                                codexId = codexId,
                                sortMode = sortMode,
                                fabRestoreState = fabRestoreState,
                                data = dataDependencies,
                                sources = sourceDependencies,
                            ) { state ->
                                SideEffect {
                                    durationOwner.synchronize(
                                        identity = codexId,
                                        posts = state.posts,
                                        resolveInBackground = state.resolveUnknownAnimatedDurations,
                                    )
                                }
                                val virtualUi = com.theoriacodex.app.ui.routes.virtualCodexUi(
                                    state, fabRestoreState, { feedFabRestoreRegistry.update(fabContext, it) },
                                    { navController.popBackStack() }, { showFollowedCreators = true },
                                    savedSearchOwner::rename, savedSearchOwner::remove,
                                )
                                CodexDetailScreen(
                                codexName = state.codex?.name,
                                onTitleLongPress = { codexActionTargetId = codexId },
                                customHeader = virtualUi?.header,
                                customFilters = virtualUi?.filters,
                                customFiltersActive = virtualUi?.filtersActive == true,
                                emptyMessage = virtualUi?.emptyMessage,
                                loading = state.loading,
                                posts = state.posts,
                                sortMode = sortMode,
                                availableSources = state.availableSources,
                                creatorBrowsingSources = state.creatorBrowsingSources,
                                pixivUgoiraClient = sourceDependencies.pixivUgoiraClient,
                                resolveUnknownAnimatedDurations = state.resolveUnknownAnimatedDurations,
                                durationStates = durationStates,
                                durationStateForPost = durationOwner::observeState,
                                tagVideoCountProvider = { source, tag ->
                                    featureDependencies.search.tagVideoCount(source, tag)
                                },
                                fetchTagVideoCounts = { source, tags ->
                                    featureDependencies.search.fetchTagVideoCounts(source, tags)
                                },
                                onDurationFilterChanged = durationOwner::onFilterChanged,
                                onDurationPostVisibilityChanged = durationOwner::onPostVisibilityChanged,
                                onDurationEnvironmentChanged = durationOwner::onEnvironmentChanged,
                                onAuthoritativeDurationKnown = durationOwner::publishPlayerDuration,
                                resolvePostById = { postId ->
                                    viewerRouteWorkflow.resolvePost(postId, ViewerStreamSource.CODEX)
                                },
                                onOpenViewer = { posts, index -> openCodexViewer(codexId, posts, index) },
                                onRemovePosts = { posts -> codexCommandOwner.remove(codexId, state.items, posts) },
                                onAddPostsToAnotherCodex = { posts ->
                                    requestSaveToCodex(posts, excludedCodexId = codexId)
                                },
                                onSavePostToDevice = { post ->
                                    libraryTransfers.downloads.submitPosts(listOf(post), originLabel = "Codex · ${state.codex?.name ?: "Collection"}")
                                    libraryTransfers.showDownloads = true
                                },
                                onPostUrlCopied = { recordPostUrlCopy() },
                                onOpenCreatorProfile = { creator ->
                                    scope.launch { openCreatorProfile(creator) }
                                },
                                onOpenLegacyCreatorProfile = { post ->
                                    scope.launch { openCreatorProfile(post) }
                                },
                                onAddIncludeTerm = { post, term ->
                                    addSearchIncludeTerm(post, term)
                                },
                                onAddExcludeTerm = { post, term ->
                                    addSearchExcludeTerm(post, term)
                                },
                                onRemoveIncludeTerm = { _, term ->
                                    removeSearchIncludeTerm(term)
                                },
                                onRemoveExcludeTerm = { _, term ->
                                    removeSearchExcludeTerm(term)
                                },
                                onFavoriteTagLongPress = addFavoriteTag,
                                onGoToSearch = {
                                    goToSearch()
                                },
                                onBack = {
                                    navController.popBackStack()
                                },
                                onDeleteCodex = { codexCommandOwner.delete(codexId, state.activeProfile.profileId) },
                                isLikesCodex = codexId == likesCodexIdForProfile(state.activeProfile.profileId),
                                fabRestoreState = fabRestoreState,
                                onFabRestoreStateChange = { updated ->
                                    feedFabRestoreRegistry.update(fabContext, updated)
                                },
                            )
                            }
                        }
                        composable(AppRoute.CreatorProfile) {
                            BrowsingDestinationStateBoundary(dataDependencies, sourceDependencies) { state ->
                                val creator = pendingCreatorProfile
                                    ?: return@BrowsingDestinationStateBoundary
                                val creatorIdentity = creator.profileId
                                    ?: creator.uploadsQuery
                                    ?: creator.displayName
                                val fabContext = creatorFeedFabContext(creator.source.name, creatorIdentity)
                                val fabRestoreState = feedFabRestoreRegistry.state(fabContext)
                                    ?: return@BrowsingDestinationStateBoundary
                                CreatorRoute(
                                coordinator = featureDependencies.creatorProfile,
                                relatedPostsLoader = featureDependencies.relatedPosts,
                                mediaDurationCoordinator = featureDependencies.mediaDurationCoordinator,
                                pixivUgoiraClient = sourceDependencies.pixivUgoiraClient,
                                config = CreatorRouteConfig(
                                    pendingLikePostIds = pendingLikePostIds[state.activeProfile.profileId].orEmpty(),
                                    followed = state.settings.followedCreators.any { it.creator.followKey() == creator.followKey() },
                                    activeCreator = creator,
                                    availableSources = state.availableSources,
                                    likedPostIds = state.likedPostIds,
                                    savedPostIds = state.savedPostIds,
                                    resolveUnknownAnimatedDurations =
                                        state.settings.contentFilters.resolveUnknownAnimatedDurations,
                                ),
                                fabRestoreState = fabRestoreState,
                                onFabRestoreStateChange = { updated ->
                                    feedFabRestoreRegistry.update(fabContext, updated)
                                },
                                callbacks = CreatorRouteCallbacks(
                                    onToggleFollow = followsOwner::toggle,
                                    onOpenViewer = { effect ->
                                        val likeAction = creatorLikeAction()
                                        viewerSessionOwner.retain(
                                            viewerRouteWorkflow.prepareResumableSession(ViewerSession(
                                                posts = effect.posts,
                                                context = effect.context,
                                                liveSearchBinding = effect.liveSearchBinding,
                                                searchVisibilityFilters = effect.visibilityFilters,
                                                onToggleLike = likeAction,
                                            )),
                                        )
                                        navController.navigate(AppRoute.Viewer) {
                                            launchSingleTop = !effect.liveSearchBinding
                                        }
                                    },
                                    onNavigateBack = { navController.popBackStack() },
                                    onToggleLike = { post ->
                                        creatorLikeAction()(post)
                                    },
                                    onRequestSaveToCodex = { post ->
                                        requestSaveToCodex(post)
                                    },
                                    onSaveToDevice = ::requestSaveToDevice,
                                    onPostUrlCopied = { recordPostUrlCopy() },
                                    onOpenUrl = { url -> openInBrowser(appContext, url) },
                                    onAddIncludeTerm = ::addSearchIncludeTerm,
                                    onAddExcludeTerm = ::addSearchExcludeTerm,
                                    onRemoveIncludeTerm = { _, term -> removeSearchIncludeTerm(term) },
                                    onRemoveExcludeTerm = { _, term -> removeSearchExcludeTerm(term) },
                                ),
                                onOwnerAvailable = creatorRouteOwnerBinding::publish,
                            )
                            }
                        }
                        composable(
                            AppRoute.Viewer,
                            // Media owns the expansion/shrink; navigation retains both destinations.
                            enterTransition = { EnterTransition.None },
                            popExitTransition = { ExitTransition.None },
                        ) {
                            ViewerReturnLifetime(this)
                            ViewerDestinationStateBoundary(
                                data = dataDependencies,
                                sources = sourceDependencies,
                                searchOwner = searchRouteOwner,
                                forYouOwner = forYouRouteOwner,
                                creatorOwner = creatorRouteOwner,
                            ) { state ->
                                ViewerRoute(
                                dependencies = ViewerRouteDependencies(
                                    onCodexUsageTargetChanged = { target ->
                                        if (navController.currentDestination?.route == AppRoute.Viewer) {
                                            appUsageTracker.onCodexTargetChanged(target)
                                        }
                                    },
                                    sessionRetentionOwner = viewerSessionOwner,
                                    canonicalizePost = { post -> featureDependencies.offlineMedia?.withoutOfflineLocations(post) ?: post },
                                    postResolver = viewerRouteWorkflow,
                                    mediaPrefetcher = ViewerMediaPrefetcher { _, media ->
                                        prefetchViewerMedia(appContext, media)
                                    },
                                    mediaDurationCoordinator = featureDependencies.mediaDurationCoordinator,
                                    restoreSession = viewerRouteWorkflow::restoreSession,
                                ),
                                renderConfig = ViewerRouteRenderConfig(
                                    playbackMode = state.browsing.settings.viewer.playbackMode,
                                    videoQuality = state.browsing.settings.viewer.videoQuality,
                                    pixivUgoiraClient = sourceDependencies.pixivUgoiraClient,
                                    tagVideoCountProvider = featureDependencies.search::tagVideoCount,
                                    fetchTagVideoCounts = featureDependencies.search::fetchTagVideoCounts,
                                    invertMultiImageScrollDirection =
                                        state.browsing.settings.viewer.invertMultiImageScrollDirection,
                                    instantSeek = state.browsing.settings.viewer.instantSeek,
                                    likedPostIds = state.browsing.likedPostIds,
                                    creatorBrowsingSources = state.browsing.creatorBrowsingSources,
                                ),
                                liveSourceState = ViewerRouteLiveSourceState(
                                    search = ViewerRouteLiveSourceSnapshot(
                                        queryHash = state.search.query.appliedQueryHash
                                            .takeIf(String::isNotBlank),
                                        results = state.search.content.results,
                                        retainedLikedPostIds = state.search.relatedPosts.retainedLikedPostIds +
                                            pendingLikePostIds[state.browsing.activeProfile.profileId].orEmpty(),
                                        canLoadMore = state.search.content.canLoadMore,
                                        loadingMore = state.search.loadingMore,
                                    ),
                                    forYou = ViewerRouteLiveSourceSnapshot(
                                        queryHash = "for_you:${state.forYou.seedId}",
                                        results = state.forYou.results,
                                        canLoadMore = state.forYou.canLoadMore,
                                        loadingMore = state.forYou.isPaging,
                                    ),
                                    creatorProfile = ViewerRouteLiveSourceSnapshot(
                                        queryHash = state.creator.queryHash,
                                        results = state.creator.results,
                                        retainedLikedPostIds = state.creator.relatedPosts.retainedLikedPostIds +
                                            pendingLikePostIds[state.browsing.activeProfile.profileId].orEmpty(),
                                        canLoadMore = state.creator.canLoadMore,
                                        loadingMore = state.creator.isPaging,
                                    ),
                                    likedPostIds = state.browsing.likedPostIds,
                                    savedPostIds = state.browsing.savedPostIds,
                                    watchedPostIds = state.browsing.watchedPostIds,
                                    unknownAnimatedDurationPolicy = state.browsing.unknownAnimatedDurationPolicy,
                                ),
                                effectCallbacks = ViewerRouteEffectCallbacks(
                                    onSavePost = { post, context ->
                                        requestSaveToCodex(
                                            post = post,
                                            fromForYou = context.streamSource == ViewerStreamSource.FOR_YOU,
                                            searchQuery = activeViewerOwner?.session?.value
                                                ?.takeIf { it.context.queryHash == context.queryHash }?.saveQuery,
                                        )
                                    },
                                    onSharePost = { post ->
                                        shareViewerPostMessage(appContext, post) {
                                            recordPostUrlCopy()
                                        }?.let { message ->
                                            Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    onDownloadMedia = { request ->
                                        if (request == null) snackbarHostState.showSnackbar("Media is still loading.")
                                        else {
                                            val launch = activeViewerOwner?.session?.value?.context
                                            val origin = when (launch?.streamSource) {
                                                ViewerStreamSource.SEARCH -> "Search"
                                                ViewerStreamSource.FOR_YOU -> "FYP"
                                                ViewerStreamSource.CODEX -> {
                                                    val id = launch.queryHash.removePrefix("codex:")
                                                    val name = dataDependencies.codexRepository.observeCodices().first()
                                                        .firstOrNull { it.codexId == id }?.name
                                                    "Codex · ${name ?: "Collection"}"
                                                }
                                                ViewerStreamSource.CREATOR_PROFILE -> "Creator profile"
                                                ViewerStreamSource.RECENTS -> "Recents"
                                                ViewerStreamSource.RELATED -> "Related posts"
                                                null -> "Viewer"
                                            }
                                            libraryTransfers.downloads.submitViewerMedia(request.post, request.media, request.pageIndex, request.totalPages, origin)
                                            libraryTransfers.showDownloads = true
                                        }
                                    },
                                    onToggleLike = { post -> toggleLikeAndSyncCodex(post) },
                                    onOpenCreatorProfile = ::openCreatorProfile,
                                    onApplyTag = { post, term, excluded ->
                                        if (excluded) {
                                            addSearchExcludeTerm(post, term.toSearchTerm())
                                        } else {
                                            addSearchIncludeTerm(post, term.toSearchTerm())
                                        }
                                    },
                                    onRecoverMedia = viewerRouteWorkflow::recoverMedia,
                                    onLoadMore = viewerRouteWorkflow::loadMore,
                                    onDismiss = {
                                        featureDependencies.search.setViewerLaunchContext(null)
                                        navController.popBackStack()
                                    },
                                    onRestorationUnavailable = {
                                        featureDependencies.search.setViewerLaunchContext(null)
                                        Toast.makeText(
                                            appContext,
                                            "Viewer session expired",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                        navController.popBackStack()
                                    },
                                ),
                                screenCallbacks = ViewerRouteScreenCallbacks(
                                    onPlaybackModeSelected = { mode ->
                                        scope.launch {
                                            dataDependencies.settingsRepository.updateSettings { current ->
                                                current.copy(viewer = current.viewer.copy(playbackMode = mode))
                                            }
                                        }
                                    },
                                    onOwnerChanged = { owner -> activeViewerOwner = owner },
                                    onVisiblePostChanged = { post, viewedMediaNumber, session ->
                                        viewerRouteWorkflow.recordVisiblePost(post, viewedMediaNumber, session)
                                    },
                                    onVisibleMediaChanged = { post, viewedMediaNumber, session ->
                                        viewerRouteWorkflow.recordVisibleMediaProgress(post, viewedMediaNumber, session)
                                    },
                                    onOpenInBrowser = { post ->
                                        post.pageUrl?.takeIf { it.isNotBlank() }?.let { url ->
                                            openInBrowser(appContext, url)
                                        }
                                    },
                                    onRemoveIncludeTerm = { _, term ->
                                        removeSearchIncludeTerm(term)
                                    },
                                    onRemoveExcludeTerm = { _, term ->
                                        removeSearchExcludeTerm(term)
                                    },
                                    onFavoriteTagLongPress = addFavoriteTag,
                                    onGoToSearch = {
                                        scope.launch { featureDependencies.search.setViewerLaunchContext(null) }
                                        goToSearch()
                                    },
                                    onOpenCreatorFallback = { post ->
                                        scope.launch { openCreatorProfile(post) }
                                    },
                                ),
                            )
                            }
                        }
                    }
                }
            }

            if (showSaveSheet && pendingSavePosts.isNotEmpty()) {
                val posts = pendingSavePosts
                SaveToCodexDestinationStateBoundary(dataDependencies, posts, pendingSaveQuery) { state ->
                    SaveToCodexSheet(
                    profiles = state.settings.recommendationProfiles,
                    initialProfileId = state.activeProfile.profileId,
                    codicesByProfile = state.codicesByProfile,
                    codexItemCounts = state.itemCounts,
                    codexCoverCandidates = state.coverCandidates,
                    relevanceScores = state.relevanceScores,
                    containingCodexIds = state.containingCodexIds,
                    excludedCodexIds = excludedCodexIdsForSave,
                    onCreateCodex = { profileId, name ->
                        codexSaveOwner.save(
                            codexId = profileScopedCodexId(profileId),
                            posts = posts,
                            cacheFullImage = state.settings.cache.cacheFullImageOnSave,
                            fromForYou = pendingSaveFromForYou,
                            newCollectionName = name,
                        )
                        clearPendingCodexSave()
                    },
                    onSelectCodex = { codexId ->
                        codexSaveOwner.save(
                            codexId = codexId,
                            posts = posts,
                            cacheFullImage = state.settings.cache.cacheFullImageOnSave,
                            fromForYou = pendingSaveFromForYou,
                        )
                        clearPendingCodexSave()
                    },
                    onDismiss = ::clearPendingCodexSave,
                )
                }
            }

            postInstallChangelog?.let { changelog ->
                PostInstallChangelogDialog(
                    releases = if (postInstallReleaseHistory.isNotEmpty()) {
                        postInstallReleaseHistory
                    } else {
                        listOf(changelog.toReleaseHistoryEntry())
                    },
                    installedVersionCode = installedVersionCode,
                    onDismiss = appShellOwner::dismissPostInstallChangelog,
                )
            }

            releaseHistoryEntries?.let { entries ->
                ReleaseHistoryDialog(
                    releases = entries,
                    installedVersionCode = installedVersionCode,
                    onDismiss = { releaseHistoryEntries = null },
                )
            }

            LibraryTransferDialogs(libraryTransfers) { item ->
                val resolved = runCatchingPreservingCancellation {
                    viewerRouteWorkflow.resolvePost(item.request.post.id, ViewerStreamSource.RECENTS)
                }.getOrNull()
                if (resolved == null) {
                    snackbarHostState.showSnackbar("This post could not be opened. Try again.")
                } else {
                    if (navController.currentBackStackEntry?.destination?.route == AppRoute.Viewer) {
                        activeViewerOwner?.clearSession()
                        viewerSessionOwner.clear()
                        navController.popBackStack(AppRoute.Viewer, inclusive = true)
                    }
                    val context = ViewerLaunchContext("downloads:${item.id}", 0, ViewerStreamSource.RECENTS, 0)
                    viewerSessionOwner.retain(ViewerSession(
                        posts = listOf(resolved), context = context, liveSearchBinding = false,
                        initialMediaIndex = item.request.selectedMedia?.pageIndex ?: 0,
                    ))
                    dataDependencies.uiRestoreRepository.setViewerLaunchContext(context)
                    navController.navigate(AppRoute.Viewer)
                }
            }
            CredentialRecoveryOverlay(
                owner = settingsOwner,
                recoveryState = sourceDependencies.accounts.recoveryState,
            ) { show ->
                if (show) AlertDialog(
                    onDismissRequest = { settingsOwner.onAction(SettingsAction.DismissCredentialRecovery) },
                    confirmButton = {
                        TextButton(
                            onClick = { settingsOwner.onAction(SettingsAction.ResetCredentialStore) },
                        ) {
                            Text("Reconnect")
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { settingsOwner.onAction(SettingsAction.DismissCredentialRecovery) },
                        ) {
                            Text("Not now")
                        }
                    },
                    title = { Text("Reconnect source accounts") },
                    text = {
                        Text(
                            "Encrypted source credentials cannot be read on this device. " +
                                "Reconnect clears the unreadable shared credential store, then opens " +
                                "Settings so Pixiv, Gelbooru, and rule34.xxx can be connected again."
                        )
                    },
                )
            }
        }
    }
    }
    }
}
