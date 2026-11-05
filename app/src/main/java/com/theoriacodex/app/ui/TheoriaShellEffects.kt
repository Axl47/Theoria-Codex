package com.theoriacodex.app.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.theoriacodex.app.BuildConfig
import com.theoriacodex.app.appshell.AppShellAction
import com.theoriacodex.app.appshell.AppShellEffect
import com.theoriacodex.app.appshell.AppShellUiState
import com.theoriacodex.app.appshell.IncomingUriKind
import com.theoriacodex.app.codex.CodexCommandHost
import com.theoriacodex.app.codex.transfer.CodexImportHost
import com.theoriacodex.app.settings.SettingsAction
import com.theoriacodex.app.settings.SettingsEffect
import com.theoriacodex.app.ui.components.MutationFeedbackHost
import com.theoriacodex.app.ui.routes.CollectRouteEffects
import com.theoriacodex.app.ui.routes.PixivAuthorizationCallbackEffect
import com.theoriacodex.app.update.PendingPostInstallChangelog
import com.theoriacodex.app.update.StartupUpdateWorkflowEffect
import com.theoriacodex.app.update.mergeReleaseHistory
import kotlinx.coroutines.flow.collect

/** Hosts one-shot feedback from the shell's owners, and keeps usage time following the process. */
@Composable
internal fun ShellOwnerHosts(shell: TheoriaShell) {
    val owners = shell.owners
    val snackbarHostState = shell.snackbarHostState
    LaunchedEffect(shell.feedFabRestoreRegistry) { shell.feedFabRestoreRegistry.load() }
    MutationFeedbackHost(shell.transfers.downloads.feedback, snackbarHostState)
    CollectRouteEffects(owners.savedSearches.effects) { snackbarHostState.showSnackbar(it) }
    MutationFeedbackHost(owners.postMutations.feedback, snackbarHostState)
    MutationFeedbackHost(shell.feedFabRestoreRegistry.feedback, snackbarHostState)
    LaunchedEffect(owners.codexSaves) {
        owners.codexSaves.effects.collect { message -> snackbarHostState.showSnackbar(message) }
    }
    val appUsageTracker = shell.features.appUsageTracker
    val autoBackup = shell.workflows.autoBackup
    DisposableEffect(appUsageTracker, autoBackup) {
        val processLifecycle = ProcessLifecycleOwner.get().lifecycle
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> appUsageTracker.onForeground()
                Lifecycle.Event.ON_STOP -> {
                    appUsageTracker.onBackground()
                    autoBackup?.onAppBackgrounded()
                }
                else -> Unit
            }
        }
        processLifecycle.addObserver(observer)
        if (processLifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            appUsageTracker.onForeground()
        }
        onDispose { processLifecycle.removeObserver(observer) }
    }
    DisposableEffect(shell.routes) {
        onDispose { shell.routes.close() }
    }
    CodexCommandHost(owners.codexCommands, snackbarHostState) { deletedId ->
        if (shell.navController.currentBackStackEntry?.arguments?.getString("codexId") == deletedId) {
            shell.navigation.homeTabRoute = TopLevelDestination.Codex.route
            shell.navController.popBackStack(AppRoute.Home, inclusive = false)
        }
    }
    LaunchedEffect(shell.navigation.homeTabRoute) {
        if (shell.navigation.homeTabRoute == TopLevelDestination.Settings.route) {
            owners.settings.onAction(SettingsAction.SettingsEntered)
        }
    }
    CodexImportHost(owners.codexImports, snackbarHostState)
}

/** Runs startup once its updater allows it, and acts on what the updater asks of the platform. */
@Composable
internal fun ShellStartupEffects(shell: TheoriaShell, appShellState: AppShellUiState) {
    val appContext = shell.appContext
    val appShellOwner = shell.appShellOwner
    LaunchedEffect(appShellState.readyToEnterApp, shell.navReady) {
        if (appShellState.readyToEnterApp && !shell.navReady) shell.completeAppStartup()
    }
    LaunchedEffect(appShellOwner) {
        appShellOwner.effects.collect { effect ->
            when (effect) {
                is AppShellEffect.Startup -> when (effect.effect) {
                    StartupUpdateWorkflowEffect.ContinueToApp -> Unit
                    StartupUpdateWorkflowEffect.OpenUnknownSourcesSettings -> openUnknownSourcesSettings(appContext)
                    is StartupUpdateWorkflowEffect.InstallerOpened -> Unit
                }
            }
        }
    }
    LaunchedEffect(appShellOwner, shell.container) {
        appShellOwner.beginStartup(
            shell.updates.startupUpdater, shell.updates.feedClient,
            installedAppVersionCode(appContext), BuildConfig.UPDATER_ENABLED,
        )
    }
}

/** Hands an incoming link to the owner that survives recreation, then acts on it once the app is ready. */
@Composable
internal fun ShellIncomingUriEffects(
    shell: TheoriaShell,
    appShellState: AppShellUiState,
    incomingUri: Uri?,
    onIncomingUriConsumed: () -> Unit,
) {
    val appContext = shell.appContext
    val appShellOwner = shell.appShellOwner
    LaunchedEffect(incomingUri) {
        val uri = incomingUri ?: return@LaunchedEffect
        appShellOwner.onAction(
            AppShellAction.AcceptIncomingUri(
                uri = uri.toString(),
                isPixivAuthorizationCallback = shell.sources.pixivAuthController.isAuthorizationCallback(uri),
                isCodexImport = isCodexImportUri(appContext, uri),
            )
        )
        // The AppShell SavedStateHandle now owns the handoff. Clear the Activity's underlying
        // singleTask intent so recreation cannot enqueue the same URI a second time.
        onIncomingUriConsumed()
    }
    val pendingPixivCallback = appShellState.pendingIncomingUri?.takeIf { it.kind == IncomingUriKind.PIXIV_AUTH_CALLBACK }
    PixivAuthorizationCallbackEffect(
        pending = pendingPixivCallback,
        sources = shell.sources,
        onConsumed = { pending -> appShellOwner.onAction(AppShellAction.ConsumeIncomingUri(pending)) },
        onCompleted = { failure -> shell.owners.settings.onAction(SettingsAction.PixivCallbackCompleted(failure)) },
    )
    val pendingCodexImport = appShellState.pendingIncomingUri?.takeIf { it.kind == IncomingUriKind.CODEX_IMPORT }
    LaunchedEffect(shell.navReady, pendingCodexImport) {
        if (!shell.navReady) return@LaunchedEffect
        val pending = pendingCodexImport ?: return@LaunchedEffect
        val uri = pending.value.toUri()
        runCatching {
            appContext.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        shell.owners.codexImports.start(uri.toString())
        appShellOwner.onAction(AppShellAction.ConsumeIncomingUri(pending))
    }
    val pendingExternalContent = appShellState.pendingIncomingUri?.takeIf { it.kind == IncomingUriKind.EXTERNAL_CONTENT }
    LaunchedEffect(shell.navReady, pendingExternalContent) {
        if (!shell.navReady) return@LaunchedEffect
        val pending = pendingExternalContent ?: return@LaunchedEffect
        shell.openExternalContent(pending.value.toUri())
        appShellOwner.onAction(AppShellAction.ConsumeIncomingUri(pending))
    }
}

/** Reports the current destination to usage statistics. */
@Composable
internal fun ShellUsageEffect(shell: TheoriaShell, currentRoute: String?, statisticsCodexId: String?) {
    val appUsageTracker = shell.features.appUsageTracker
    val homeTabRoute = shell.navigation.homeTabRoute
    LaunchedEffect(appUsageTracker, currentRoute, homeTabRoute, statisticsCodexId) {
        appUsageTracker.onCategoryChanged(statisticsUsageCategory(currentRoute, homeTabRoute))
        if (currentRoute != AppRoute.Viewer) {
            appUsageTracker.onCodexTargetChanged(
                statisticsCodexId?.takeIf { currentRoute == AppRoute.CodexDetail }
                    ?.let { com.theoriacodex.data.repository.CodexUsageTarget(it) },
            )
        }
    }
}

/** Dismisses the keyboard as soon as the home pager starts to move. */
@Composable
internal fun PagerScrollFocusEffect(pagerState: PagerState) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(pagerState.isScrollInProgress) {
        if (pagerState.isScrollInProgress) {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
    }
}

@Composable
internal fun ShellFollowAndSettingsEffects(
    shell: TheoriaShell,
    currentRoute: String?,
    latestInstalledChangelog: PendingPostInstallChangelog?,
) {
    val appContext = shell.appContext
    val owners = shell.owners
    CollectRouteEffects(owners.follows.messages) { message -> shell.toast(message) }
    CollectRouteEffects(owners.settings.effects) { effect ->
        when (effect) {
            is SettingsEffect.OpenExternalUri -> openInBrowser(appContext, effect.uri)
            is SettingsEffect.ShowMessage -> Toast.makeText(
                appContext,
                effect.message,
                if (effect.long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
            ).show()
            SettingsEffect.LoadChangelog -> loadReleaseHistory(shell, latestInstalledChangelog)
            SettingsEffect.ThumbnailCacheCleared -> shell.thumbnailCacheGeneration += 1
            SettingsEffect.NavigateToSettings -> navigateToSettings(shell, currentRoute)
        }
    }
}

private suspend fun loadReleaseHistory(shell: TheoriaShell, latestInstalledChangelog: PendingPostInstallChangelog?) {
    try {
        val remoteHistory = shell.updates.feedClient.mainPrereleaseHistory(limit = 50).getOrElse { emptyList() }
        val merged = mergeReleaseHistory(remoteHistory = remoteHistory, localCurrent = latestInstalledChangelog)
        if (merged.isEmpty()) {
            shell.toast("No changelog available yet")
        } else {
            shell.overlays.releaseHistoryEntries = merged
        }
    } finally {
        shell.owners.settings.onAction(SettingsAction.ChangelogRequestFinished)
    }
}

private fun navigateToSettings(shell: TheoriaShell, currentRoute: String?) {
    val navigation = shell.navigation
    navigation.pendingTopLevelRoute = TopLevelDestination.Settings.route
    navigation.homeTabRoute = TopLevelDestination.Settings.route
    if (shell.navReady && currentRoute != AppRoute.Home) {
        val returnedHome = navigation.navController.popBackStack(AppRoute.Home, inclusive = false)
        if (!returnedHome) {
            navigation.navController.navigate(AppRoute.Home) { launchSingleTop = true }
        }
    }
}

/** Restores the saved home tab, applies requested jumps, and saves the tab once the pager rests. */
@Composable
internal fun HomeTabEffects(shell: TheoriaShell, currentRoute: String?) {
    val navigation = shell.navigation
    val pagerState = navigation.pagerState
    val onHome = shell.navReady && currentRoute == AppRoute.Home
    var homeTabRestoreComplete by remember { mutableStateOf(false) }
    var persistedHomeTabRoute by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(shell.navReady, currentRoute) {
        if (!onHome) return@LaunchedEffect
        pagerState.scrollToRoute(navigation.homeTabRoute)
        homeTabRestoreComplete = true
    }
    LaunchedEffect(shell.navReady, currentRoute, navigation.pendingTopLevelRoute) {
        val targetRoute = navigation.pendingTopLevelRoute
        if (targetRoute == null || !onHome) return@LaunchedEffect
        pagerState.scrollToRoute(targetRoute)
        navigation.pendingTopLevelRoute = null
    }
    LaunchedEffect(shell.navReady, currentRoute, homeTabRestoreComplete, pagerState.currentPage, pagerState.isScrollInProgress) {
        if (!onHome || !homeTabRestoreComplete || pagerState.isScrollInProgress) return@LaunchedEffect
        val route = TopLevelDestination.entries[pagerState.currentPage.coerceIn(0, TopLevelDestination.entries.lastIndex)].route
        navigation.homeTabRoute = route
        if (persistedHomeTabRoute != route) {
            shell.data.uiRestoreRepository.setLastTab(route)
            persistedHomeTabRoute = route
        }
    }
}

private suspend fun PagerState.scrollToRoute(route: String) {
    val targetIndex = TopLevelDestination.entries.indexOfFirst { destination -> destination.route == route }.coerceAtLeast(0)
    if (targetIndex != currentPage) scrollToPage(targetIndex)
}

/** Platform state that follows the Viewer, and the return from the system package installer. */
@Composable
internal fun ShellPlatformEffects(shell: TheoriaShell, currentRoute: String?) {
    val hostContext = LocalContext.current
    val hostActivity = remember(hostContext) { hostContext.findActivity() }
    TheoriaAppPlatformEffects(
        hostActivity = hostActivity,
        viewerActive = currentRoute == AppRoute.Viewer,
        orientation = LocalConfiguration.current.orientation,
    )
    val lifecycleOwner = LocalLifecycleOwner.current
    val appShellOwner = shell.appShellOwner
    val appContext = shell.appContext
    DisposableEffect(lifecycleOwner, appShellOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) appShellOwner.onInstallerReturn(installedAppVersionCode(appContext))
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}
