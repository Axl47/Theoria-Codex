package com.theoriacodex.app.ui

import android.net.Uri
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.combine
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.http.SiteHealthCheck
import com.theoriacodex.sources.http.HostOutageKind
import com.theoriacodex.app.source.inPresentationOrder
import com.theoriacodex.app.source.startSourceHealthCheck
import com.theoriacodex.app.source.sourceIssues
import com.theoriacodex.app.source.clearSource
import com.theoriacodex.app.source.SourceHealth
import com.theoriacodex.app.source.LocalSourceHealth
import com.theoriacodex.app.ui.routes.activeRecommendationProfile
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.theoriacodex.app.tags.PostTagSavedTags
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import com.theoriacodex.app.R
import com.theoriacodex.app.appshell.AppShellUiState
import com.theoriacodex.app.appshell.AppShellViewModel
import com.theoriacodex.app.appshell.StartupUpdateChoice
import com.theoriacodex.app.appshell.ViewerSessionRetentionViewModel
import com.theoriacodex.app.codex.CodexCollectionSource
import com.theoriacodex.app.codex.CodexSaveRelevanceLoader
import com.theoriacodex.app.codex.LocalCodexSaveRelevanceLoader
import com.theoriacodex.app.codex.SaveToCodexSheet
import com.theoriacodex.app.codex.profileScopedCodexId
import com.theoriacodex.app.di.TheoriaAppContainer
import com.theoriacodex.app.network.AppHttpClients
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.app.settings.SettingsAction
import com.theoriacodex.app.tags.PostTagContextProvider
import com.theoriacodex.app.ui.routes.ActiveProfileCoordinationEffect
import com.theoriacodex.app.ui.routes.CredentialRecoveryOverlay
import com.theoriacodex.app.ui.routes.SaveToCodexDestinationStateBoundary
import com.theoriacodex.app.ui.theme.TheoriaNightTheme
import com.theoriacodex.app.update.StartupUpdateState
import com.theoriacodex.app.update.toReleaseHistoryEntry
import com.theoriacodex.app.viewer.LocalViewerReturnTransition
import com.theoriacodex.app.viewer.ViewerReturnTransition
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/** Ranks Codices for "Save to Codex" from the saved library. */
@Composable
private fun rememberCodexSaveRelevanceLoader(shell: TheoriaShell): CodexSaveRelevanceLoader = remember(shell) {
    val source = CodexCollectionSource(shell.data.codexRepository, shell.data.storageDirectory, shell.data.cacheRepository)
    CodexSaveRelevanceLoader(shell.data.codexRepository, shell.scope, observeLibrary = source::observeSavedLibrary)
}

/** The active profile's single saved tags, for the star in post sheets, and how to unsave one. */
@Composable
private fun rememberPostTagSavedTags(shell: TheoriaShell): PostTagSavedTags = remember(shell) {
    PostTagSavedTags(
        saved = shell.data.settingsRepository.observeSettings().map { settings ->
            settings.favoriteTagsByProfile[settings.activeRecommendationProfile().profileId].orEmpty()
                .mapNotNullTo(mutableSetOf()) { entry -> entry.tags.singleOrNull()?.let { entry.source to it } }
        }.distinctUntilChanged(),
        remove = { source, tag -> shell.removeFavoriteTags(source, listOf(tag)) },
    )
}

/** Renderable app-shell boundary with dependencies supplied by the application owner. */
@Composable
internal fun TheoriaAppContent(
    appContainer: TheoriaAppContainer,
    viewerSessionOwner: ViewerSessionRetentionViewModel,
    appShellOwner: AppShellViewModel,
    incomingUri: Uri? = null,
    onIncomingUriConsumed: () -> Unit = {},
) {
    val shell = rememberTheoriaShell(appContainer, viewerSessionOwner, appShellOwner)
    ShellOwnerHosts(shell)
    val appShellState by appShellOwner.state.collectAsStateWithLifecycle()
    shell.overlays.codexActionTargetId?.let { id ->
        // This overlay is composed above the app shell's theme, so it must apply the theme itself.
        TheoriaNightTheme {
            key(id) { CodexLibraryDestination(shell, actionCodexId = id) }
        }
    }
    ShellStartupEffects(shell, appShellState)
    ShellIncomingUriEffects(shell, appShellState, incomingUri, onIncomingUriConsumed)
    val backStackEntry by shell.navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    ShellUsageEffect(shell, currentRoute, backStackEntry?.arguments?.getString("codexId"))
    PagerScrollFocusEffect(shell.navigation.pagerState)
    ShellFollowAndSettingsEffects(shell, currentRoute, appShellState.latestInstalledChangelog)
    HomeTabEffects(shell, currentRoute)
    ShellPlatformEffects(shell, currentRoute)
    ActiveProfileCoordinationEffect(
        settingsRepository = shell.data.settingsRepository,
        onProfileChanged = { profile -> shell.workflows.likesCodexSync.ensureProfileCodex(profile) },
    )
    val saveRelevanceLoader = rememberCodexSaveRelevanceLoader(shell)
    val watchPresentation = com.theoriacodex.app.search.rememberPostWatchPresentation(
        shell.data.readingPositions, shell.data.recentsRepository,
    )
    val viewerReturnTransition = remember { ViewerReturnTransition() }
    val relatedPostsSupport = remember(shell) { shell.features.relatedPosts::supports }
    val sourceHealth = rememberSourceHealth(shell)
    val savedTags = rememberPostTagSavedTags(shell)
    CompositionLocalProvider(
        com.theoriacodex.app.search.LocalPostWatchPresentation provides watchPresentation,
        LocalCodexSaveRelevanceLoader provides saveRelevanceLoader,
        LocalViewerReturnTransition provides viewerReturnTransition,
        LocalNetworkOnline provides shell.sources.networkOnline,
        LocalSourceHealth provides sourceHealth,
        com.theoriacodex.app.related.LocalRelatedPostsSupport provides relatedPostsSupport,
    ) {
        com.theoriacodex.app.codex.SavedCodexSession {
            PostTagContextProvider(
                shell.routes.searchOwner?.state,
                shell.features.search::fetchSearchTermCounts,
                shell.routes.postTagSearchActions,
                savedTags,
            ) {
                TheoriaNightTheme {
                    ShellOverlaySheets(shell)
                    if (shell.navReady) {
                        TheoriaNavigationSurface(shell, currentRoute)
                    } else {
                        StartupSurface(shell, appShellState)
                    }
                    ShellDialogs(shell, appShellState)
                }
            }
        }
    }
}

@Composable
private fun ShellOverlaySheets(shell: TheoriaShell) {
    val overlays = shell.overlays
    if (overlays.showSavedSearchManagement) {
        val context = com.theoriacodex.app.ui.components.codexFeedFabContext(com.theoriacodex.app.codex.SAVED_SEARCHES_CODEX_ID)
        shell.feedFabRestoreRegistry.state(context)?.let { restore ->
            com.theoriacodex.app.codex.SavedSearchesManagementSheet(
                shell.savedSearches, restore, { shell.feedFabRestoreRegistry.update(context, it) },
                shell.owners.savedSearches::rename, shell.owners.savedSearches::remove,
                onDismiss = { overlays.showSavedSearchManagement = false },
            )
        }
    }
    if (overlays.showFollowedCreators) {
        com.theoriacodex.app.creator.FollowedCreatorsSheet(
            owner = shell.owners.follows,
            onDismiss = { overlays.showFollowedCreators = false },
            onOpen = { creator ->
                overlays.showFollowedCreators = false
                shell.launchOpenCreator(creator)
            },
        )
    }
}

/** The splash, and whatever the updater needs decided before the app can open. */
@Composable
private fun StartupSurface(shell: TheoriaShell, appShellState: AppShellUiState) {
    val appShellOwner = shell.appShellOwner
    val startup = appShellState.startup
    val promptRemote = startup.choice
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
        if (startup.updateState is StartupUpdateState.AwaitingUserChoice && promptRemote != null) {
            StartupUpdatePromptCard(
                releases = appShellState.startupReleaseHistory.ifEmpty { listOf(promptRemote.toReleaseHistoryEntry()) },
                installedVersionCode = rememberInstalledVersionCode(shell),
                actionEnabled = !startup.actionLocked,
                onYes = { appShellOwner.chooseUpdate(StartupUpdateChoice.INSTALL) },
                onNo = { appShellOwner.chooseUpdate(StartupUpdateChoice.SKIP) },
                onRemindLater = { appShellOwner.chooseUpdate(StartupUpdateChoice.REMIND_LATER) },
            )
        } else {
            if (startup.updateState !is StartupUpdateState.Failed) CircularProgressIndicator()
            Text(text = startup.statusMessage, color = Color.White)
            if (startup.awaitingInstallerReturn) {
                TextButton(
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                    onClick = appShellOwner::continueCurrentVersion,
                ) {
                    Text(text = "Continue current version", color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun rememberInstalledVersionCode(shell: TheoriaShell): Int =
    remember(shell.appContext) { installedAppVersionCode(shell.appContext) }

/** The adaptive scaffold and its navigation graph, once the app is ready to show them. */
@Composable
private fun TheoriaNavigationSurface(shell: TheoriaShell, currentRoute: String?) {
    val navigation = shell.navigation
    val pagerState = navigation.pagerState
    val density = LocalDensity.current
    val windowHeightDp = LocalWindowInfo.current.containerSize.height / density.density
    val bottomBarWindowInsets = NavigationBarDefaults.windowInsets
    val bottomSystemInsetDp = with(density) { bottomBarWindowInsets.getBottom(this).toDp().value }
    val bottomNavigationSizing = remember(windowHeightDp, bottomSystemInsetDp) {
        calculateBottomNavigationSizing(windowHeightDp = windowHeightDp, bottomSystemInsetDp = bottomSystemInsetDp)
    }
    val tabReselects = remember { MutableSharedFlow<TopLevelDestination>(extraBufferCapacity = 1) }
    TheoriaAdaptiveScaffold(
        navigationVisible = currentRoute == AppRoute.Home,
        selectedIndex = pagerState.currentPage.coerceIn(0, TopLevelDestination.entries.lastIndex),
        bottomBarHeight = bottomNavigationSizing.totalHeightDp.dp,
        bottomBarIconSize = bottomNavigationSizing.iconSizeDp.dp,
        bottomBarWindowInsets = bottomBarWindowInsets,
        snackbarHostState = shell.snackbarHostState,
        // The status bar's inset shows this color, and the Viewer's letterboxing is black.
        containerColor = if (currentRoute == AppRoute.Viewer) Color.Black else MaterialTheme.colorScheme.background,
        onDestinationSelected = { destination ->
            val alreadyShowing = navigation.currentRoute == AppRoute.Home &&
                TopLevelDestination.entries[pagerState.currentPage] == destination
            navigation.homeTabRoute = destination.route
            if (alreadyShowing) tabReselects.tryEmit(destination)
            else shell.scope.launch { navigateTopLevelDestination(navigation.navController, pagerState, destination) }
        },
    ) { contentModifier ->
        CompositionLocalProvider(LocalTabReselects provides tabReselects) {
            SharedTransitionLayout(contentModifier) {
                TheoriaNavHost(shell, this)
            }
        }
    }
}

@Composable
private fun TheoriaNavHost(shell: TheoriaShell, sharedScope: androidx.compose.animation.SharedTransitionScope) {
    NavHost(
        navController = shell.navController,
        startDestination = AppRoute.Home,
        enterTransition = {
            if (targetState.destination.route == AppRoute.CodexDetail) codexEnterTransition()
            else pushEnterTransition()
        },
        exitTransition = {
            when (targetState.destination.route) {
                AppRoute.Viewer -> ExitTransition.None
                AppRoute.CodexDetail -> codexUnderneathExit()
                else -> pushExitTransition()
            }
        },
        popEnterTransition = {
            when (initialState.destination.route) {
                AppRoute.Viewer -> EnterTransition.None
                AppRoute.CodexDetail -> codexUnderneathEnter()
                else -> popEnterTransition()
            }
        },
        popExitTransition = {
            if (initialState.destination.route == AppRoute.CodexDetail) codexExitTransition()
            else popExitTransition()
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        theoriaDestinations(shell, sharedScope)
    }
}

@Composable
private fun ShellDialogs(shell: TheoriaShell, appShellState: AppShellUiState) {
    val overlays = shell.overlays
    if (shell.pendingSave.visible && shell.pendingSave.posts.isNotEmpty()) SaveToCodexOverlay(shell)
    appShellState.postInstallChangelog?.let { changelog ->
        PostInstallChangelogDialog(
            releases = appShellState.postInstallReleaseHistory.ifEmpty { listOf(changelog.toReleaseHistoryEntry()) },
            installedVersionCode = rememberInstalledVersionCode(shell),
            onDismiss = shell.appShellOwner::dismissPostInstallChangelog,
        )
    }
    overlays.releaseHistoryEntries?.let { entries ->
        ReleaseHistoryDialog(
            releases = entries,
            installedVersionCode = rememberInstalledVersionCode(shell),
            onDismiss = { overlays.releaseHistoryEntries = null },
        )
    }
    LibraryTransferDialogs(shell.transfers) { item -> shell.openDownloadedPost(item) }
    CredentialRecoveryOverlay(
        owner = shell.owners.settings,
        recoveryState = shell.sources.accounts.recoveryState,
    ) { show ->
        if (show) CredentialRecoveryDialog(
            onDismiss = { shell.owners.settings.onAction(SettingsAction.DismissCredentialRecovery) },
            onReconnect = { shell.owners.settings.onAction(SettingsAction.ResetCredentialStore) },
        )
    }
}

@Composable
private fun SaveToCodexOverlay(shell: TheoriaShell) {
    val pendingSave = shell.pendingSave
    val posts = pendingSave.posts
    SaveToCodexDestinationStateBoundary(shell.data, posts, pendingSave.query) { state ->
        SaveToCodexSheet(
            profiles = state.settings.recommendationProfiles,
            initialProfileId = state.activeProfile.profileId,
            codicesByProfile = state.codicesByProfile,
            codexItemCounts = state.itemCounts,
            codexCoverCandidates = state.coverCandidates,
            relevanceScores = remember(state.relevanceScores, pendingSave.query) {
                pendingSave.searchSaves.boost(pendingSave.query, state.relevanceScores)
            },
            librarySort = state.settings.codexLibrarySort,
            libraryUsage = state.libraryUsage,
            containingCodexIds = state.containingCodexIds,
            excludedCodexIds = pendingSave.excludedCodexIds,
            onCreateCodex = { profileId, name ->
                val codexId = profileScopedCodexId(profileId)
                shell.owners.codexSaves.save(
                    codexId = codexId,
                    posts = posts,
                    cacheFullImage = state.settings.cache.cacheFullImageOnSave,
                    fromForYou = pendingSave.fromForYou,
                    newCollectionName = name,
                )
                pendingSave.searchSaves.record(pendingSave.query, codexId)
                pendingSave.clear()
            },
            onSelectCodex = { codexId ->
                shell.owners.codexSaves.save(
                    codexId = codexId,
                    posts = posts,
                    cacheFullImage = state.settings.cache.cacheFullImageOnSave,
                    fromForYou = pendingSave.fromForYou,
                )
                pendingSave.searchSaves.record(pendingSave.query, codexId)
                pendingSave.clear()
            },
            onDismiss = pendingSave::clear,
        )
    }
}

/**
 * Down sources for the dropdowns, refreshed when outages change and each minute so expired ones
 * clear. Starts the background startup check once the app is showing (it never blocks startup).
 */
@Composable
private fun rememberSourceHealth(shell: TheoriaShell): SourceHealth {
    val outages = shell.sources.outages
    LaunchedEffect(shell.navReady) {
        if (shell.navReady) {
            startSourceHealthCheck(shell.scope, SiteHealthCheck(outages, AppHttpClients.sources)) {
                shell.sources.availableSources.value.inPresentationOrder()
            }
            // An expired Pixiv token would otherwise refresh inside the first search. No account is not an error here.
            shell.scope.launch { runCatchingPreservingCancellation { shell.sources.pixivTokens.activeTokens() } }
        }
    }
    val issues by produceState(emptyMap<SourceKey, HostOutageKind>(), outages) {
        combine(outages.outages, shell.sources.availableSources, minuteTicks()) { recorded, sources, _ ->
            sourceIssues(recorded, sources, System.currentTimeMillis())
        }.collect { value = it }
    }
    return remember(issues, shell) {
        SourceHealth(
            issues = issues,
            recheck = outages::clearSource,
            verify = { source ->
                shell.container.customSites.requestVerification(source.id)
                shell.owners.settings.onAction(SettingsAction.OpenCustomSites)
            },
        )
    }
}

private fun minuteTicks() = flow {
    while (true) {
        emit(Unit)
        delay(60_000L)
    }
}
