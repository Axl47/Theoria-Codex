package com.theoriacodex.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.app.DownloadManager
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.os.Build
import android.webkit.URLUtil
import android.widget.Toast
import com.google.gson.Gson
import com.google.gson.JsonObject
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.R
import com.theoriacodex.app.BuildConfig
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.core.content.pm.PackageInfoCompat
import com.theoriacodex.app.media.isAnimatedPost
import com.theoriacodex.app.media.isPixivUgoiraPost
import com.theoriacodex.app.codex.CodexDetailScreen
import com.theoriacodex.app.codex.CodexListScreen
import com.theoriacodex.app.codex.SaveToCodexSheet
import com.theoriacodex.app.explore.ExploreScreen
import com.theoriacodex.app.search.SearchCoordinator
import com.theoriacodex.app.search.SearchScreen
import com.theoriacodex.app.search.FileBackedTagSuggestionStore
import com.theoriacodex.app.settings.SettingsScreen
import com.theoriacodex.app.sourceauth.AndroidSecureSourceCredentialsStore
import com.theoriacodex.app.sourceauth.parseGelbooruCredentialInput
import com.theoriacodex.app.sourceauth.PixivPkceController
import com.theoriacodex.app.ui.theme.TheoriaNightTheme
import com.theoriacodex.app.update.AndroidApkInstaller
import com.theoriacodex.app.update.ApkDownloadManager
import com.theoriacodex.app.update.ApkUpdateValidator
import com.theoriacodex.app.update.ChangelogSection
import com.theoriacodex.app.update.FileBackedUpdateStateStore
import com.theoriacodex.app.update.GitHubReleaseFeedClient
import com.theoriacodex.app.update.RemoteUpdate
import com.theoriacodex.app.update.PendingPostInstallChangelog
import com.theoriacodex.app.update.StartupUpdateOutcome
import com.theoriacodex.app.update.StartupUpdateState
import com.theoriacodex.app.update.StartupUpdater
import com.theoriacodex.app.update.UnknownSourcesPermissionRequiredException
import com.theoriacodex.app.update.messageText
import com.theoriacodex.app.viewer.PixivUgoiraClient
import com.theoriacodex.app.viewer.ViewerScreen
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.CacheSnapshot
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.data.repository.FileBackedCacheRepository
import com.theoriacodex.data.repository.FileBackedCodexRepository
import com.theoriacodex.data.repository.FileBackedQueryRepository
import com.theoriacodex.data.repository.FileBackedSettingsRepository
import com.theoriacodex.data.repository.FileBackedUiRestoreRepository
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.RealAdapterRegistry
import com.theoriacodex.sources.credentials.GelbooruCredentials
import com.theoriacodex.sources.http.DefaultSourceHttpClient
import com.theoriacodex.sources.pixiv.PixivAuthApi
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlin.math.abs

enum class TopLevelDestination(val route: String, val label: String) {
    Search("search", "Search"),
    Explore("explore", "Explore"),
    Codex("codex", "Codex"),
    Settings("settings", "Settings"),
}

private object AppRoute {
    const val Viewer = "viewer"
    const val CodexDetail = "codex/detail/{codexId}"

    fun codexDetail(codexId: String): String {
        return "codex/detail/$codexId"
    }
}

private data class ViewerSession(
    val posts: List<Post>,
    val context: ViewerLaunchContext,
    val liveSearchBinding: Boolean = false,
    val searchAnimatedOnly: Boolean = false,
)

private data class ReleaseChangelogEntry(
    val releaseId: Long?,
    val versionCode: Int,
    val commitShaShort: String,
    val releaseName: String?,
    val publishedAtEpochMs: Long?,
    val changelogMarkdown: String,
    val changelogSections: List<ChangelogSection>,
)

@Composable
fun TheoriaApp(
    incomingUri: Uri? = null,
    onIncomingUriConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    val appContext = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()

    val storageDirectory = remember(appContext) { File(appContext.filesDir, "theoria_codex") }
    val seedTagSuggestions = remember(appContext) {
        loadSeedTagSuggestions(appContext)
    }
    val tagSuggestionStore = remember(storageDirectory, seedTagSuggestions) {
        FileBackedTagSuggestionStore(
            storeFile = File(storageDirectory, "tag_suggestions.json"),
            seedData = seedTagSuggestions,
        )
    }
    val sourceHttpClient = remember { DefaultSourceHttpClient() }
    val pixivAuthApi = remember(sourceHttpClient) { PixivAuthApi(sourceHttpClient) }
    val credentialsStore = remember(appContext) { AndroidSecureSourceCredentialsStore(appContext) }
    val pixivAuthController = remember(credentialsStore, pixivAuthApi) {
        PixivPkceController(
            authApi = pixivAuthApi,
            credentialsProvider = credentialsStore,
        )
    }
    val pixivUgoiraClient = remember(credentialsStore, sourceHttpClient) {
        PixivUgoiraClient(
            credentialsProvider = credentialsStore,
            httpClient = sourceHttpClient,
        )
    }
    val realRegistry = remember(credentialsStore, sourceHttpClient) {
        RealAdapterRegistry(
            credentialsProvider = credentialsStore,
            httpClient = sourceHttpClient,
            exposedSources = setOf(SourceKey.PIXIV, SourceKey.GELBOORU),
        )
    }
    val updateStateStore = remember(storageDirectory) {
        FileBackedUpdateStateStore(
            file = File(storageDirectory, "update_state.json"),
        )
    }
    val updateFeedClient = remember {
        GitHubReleaseFeedClient(
            owner = BuildConfig.UPDATE_REPO_OWNER,
            repo = BuildConfig.UPDATE_REPO_NAME,
            channel = BuildConfig.UPDATE_CHANNEL,
            assetName = BuildConfig.UPDATE_ASSET_NAME,
        )
    }
    val apkDownloadManager = remember(appContext) {
        ApkDownloadManager(
            context = appContext,
            outputFileName = BuildConfig.UPDATE_ASSET_NAME,
        )
    }
    val apkUpdateValidator = remember(appContext) { ApkUpdateValidator(appContext) }
    val apkInstaller = remember(appContext) { AndroidApkInstaller(appContext) }
    val startupUpdater = remember(
        appContext,
        updateFeedClient,
        apkDownloadManager,
        apkUpdateValidator,
        apkInstaller,
        updateStateStore,
    ) {
        StartupUpdater(
            context = appContext,
            feedClient = updateFeedClient,
            downloadManager = apkDownloadManager,
            validator = apkUpdateValidator,
            installer = apkInstaller,
            stateStore = updateStateStore,
            updateCheckTimeoutMs = BuildConfig.UPDATE_CHECK_TIMEOUT_MS,
        )
    }

    val codexRepository = remember(storageDirectory) { FileBackedCodexRepository(storageDirectory) }
    val queryRepository = remember(storageDirectory) { FileBackedQueryRepository(storageDirectory) }
    val settingsRepository = remember(storageDirectory) { FileBackedSettingsRepository(storageDirectory) }
    val cacheRepository = remember(storageDirectory) { FileBackedCacheRepository(storageDirectory) }
    val uiRestoreRepository = remember(storageDirectory) { FileBackedUiRestoreRepository(storageDirectory) }
    val searchCoordinator = remember {
        SearchCoordinator(
            registry = realRegistry,
            queryRepository = queryRepository,
            settingsRepository = settingsRepository,
            uiRestoreRepository = uiRestoreRepository,
            tagSuggestionStore = tagSuggestionStore,
        )
    }

    val settings by settingsRepository.observeSettings().collectAsState(initial = AppSettings())
    val cacheSnapshot by cacheRepository.observeSnapshot().collectAsState(
        initial = CacheSnapshot(thumbnailCount = 0, fullImageCount = 0),
    )
    val codices by codexRepository.observeCodices().collectAsState(initial = emptyList())

    var viewerSession by remember { mutableStateOf<ViewerSession?>(null) }
    var showSaveSheet by remember { mutableStateOf(false) }
    var pendingSavePost by remember { mutableStateOf<Post?>(null) }
    var startDestination by rememberSaveable { mutableStateOf(TopLevelDestination.Search.route) }
    var navReady by remember { mutableStateOf(false) }
    var startupUpdateState by remember { mutableStateOf<StartupUpdateState>(StartupUpdateState.Checking) }
    var startupStatusMessage by remember { mutableStateOf("Checking for updates...") }
    var updateChoiceRemote by remember { mutableStateOf<RemoteUpdate?>(null) }
    var startupUpdateReleaseHistory by remember { mutableStateOf<List<ReleaseChangelogEntry>>(emptyList()) }
    var postInstallChangelog by remember { mutableStateOf<PendingPostInstallChangelog?>(null) }
    var postInstallReleaseHistory by remember { mutableStateOf<List<ReleaseChangelogEntry>>(emptyList()) }
    var latestInstalledChangelog by remember { mutableStateOf<PendingPostInstallChangelog?>(null) }
    var releaseHistoryEntries by remember { mutableStateOf<List<ReleaseChangelogEntry>?>(null) }
    var releaseHistoryLoading by remember { mutableStateOf(false) }
    var startupActionLocked by remember { mutableStateOf(false) }
    var pendingInstallRemote by remember { mutableStateOf<RemoteUpdate?>(null) }
    var awaitingUnknownSources by remember { mutableStateOf(false) }
    var awaitingInstallerReturn by remember { mutableStateOf(false) }
    var pendingPixivDeepLinkUri by remember { mutableStateOf<Uri?>(null) }
    val codexItemCounts = remember { mutableStateMapOf<String, Int>() }

    var pixivStatusLabel by remember { mutableStateOf("Not connected") }
    var pixivConnected by remember { mutableStateOf(false) }
    var gelbooruStatusLabel by remember { mutableStateOf("Not configured") }
    var gelbooruUserIdInput by rememberSaveable { mutableStateOf("") }
    var gelbooruApiKeyInput by rememberSaveable { mutableStateOf("") }

    suspend fun refreshSourceAccountState() {
        val pixivTokens = credentialsStore.getPixivTokens()
        pixivStatusLabel = when {
            pixivTokens == null -> {
                pixivConnected = false
                "Not connected"
            }
            pixivTokens.expiresAtEpochMs <= System.currentTimeMillis() -> {
                pixivStatusLabel = "Connected (refreshing token...)"
                val refreshResult = withTimeoutOrNull(PIXIV_TOKEN_REFRESH_TIMEOUT_MS) {
                    runCatching { pixivAuthApi.refresh(pixivTokens.refreshToken) }
                }
                when {
                    refreshResult == null -> {
                        pixivConnected = false
                        "Connected (refresh timed out, retry on next request)"
                    }
                    refreshResult.isSuccess -> {
                        credentialsStore.savePixivTokens(requireNotNull(refreshResult.getOrNull()))
                        pixivConnected = true
                        "Connected"
                    }
                    else -> {
                        val failure = refreshResult.exceptionOrNull()
                        if (
                            failure is SourceAdapterException &&
                            (failure.reason == SourceFailureReason.AUTH_EXPIRED ||
                                failure.reason == SourceFailureReason.AUTH_REQUIRED)
                        ) {
                            credentialsStore.clearPixivTokens()
                            pixivConnected = false
                            "Not connected (session expired)"
                        } else {
                            pixivConnected = false
                            "Connected (refresh failed, retry on next request)"
                        }
                    }
                }
            }
            else -> {
                pixivConnected = true
                "Connected"
            }
        }

        val gelbooruCredentials = credentialsStore.getGelbooruCredentials()
        if (gelbooruCredentials == null) {
            gelbooruStatusLabel = "Not configured"
            gelbooruUserIdInput = ""
            gelbooruApiKeyInput = ""
        } else {
            gelbooruStatusLabel = "Configured"
            gelbooruUserIdInput = gelbooruCredentials.userId
            gelbooruApiKeyInput = gelbooruCredentials.apiKey
        }
    }

    fun requestSaveToDevice(post: Post) {
        scope.launch {
            val resultLabel = if (isPixivUgoiraPost(post)) {
                pixivUgoiraClient.exportToMp4(
                    context = appContext,
                    postId = post.id.sourcePostId,
                    title = post.title,
                ).fold(
                    onSuccess = { "Saved video to device" },
                    onFailure = { "Could not save video" },
                )
            } else {
                if (enqueuePostDownload(appContext, post)) {
                    "Download queued"
                } else {
                    "Could not queue download"
                }
            }
            Toast.makeText(appContext, resultLabel, Toast.LENGTH_SHORT).show()
        }
    }

    suspend fun completeAppStartup() {
        if (navReady) return
        searchCoordinator.initialize()
        refreshSourceAccountState()
        startDestination = uiRestoreRepository.getLastTab()
            ?: settingsRepository.observeSettings().first().lastSelectedTabRoute
        navReady = true
        val updateSnapshot = startupUpdater.pendingSnapshot()
        latestInstalledChangelog = updateSnapshot.lastInstalledChangelog
        val pendingChangelog = updateSnapshot.pendingPostInstallChangelog
        if (pendingChangelog != null) {
            val installedVersionCode = installedAppVersionCode(appContext)
            if (pendingChangelog.versionCode <= installedVersionCode) {
                postInstallChangelog = pendingChangelog
                latestInstalledChangelog = pendingChangelog
                val merged = mergeReleaseHistory(
                    remoteHistory = updateFeedClient.mainPrereleaseHistory(limit = 100)
                        .getOrElse { emptyList() },
                    localCurrent = pendingChangelog,
                )
                val fromVersionCode = pendingChangelog.fromVersionCode ?: (pendingChangelog.versionCode - 1)
                val updatesSincePreviousInstall = merged
                    .filter { entry ->
                        entry.versionCode > fromVersionCode && entry.versionCode <= installedVersionCode
                    }
                    .sortedWith(releaseChangelogNewestFirstComparator())
                postInstallReleaseHistory = if (updatesSincePreviousInstall.isNotEmpty()) {
                    updatesSincePreviousInstall
                } else {
                    listOf(pendingChangelog.toReleaseHistoryEntry())
                }
            }
        }
    }

    suspend fun continueAfterUpdateFailure(message: String) {
        updateChoiceRemote = null
        startupUpdateReleaseHistory = emptyList()
        startupActionLocked = false
        startupUpdateState = StartupUpdateState.Failed(message)
        startupStatusMessage = message
        delay(1_200)
        completeAppStartup()
    }

    suspend fun performStartupInstall(remote: RemoteUpdate) {
        startupActionLocked = true
        startupUpdater.onUserChoseYes(remote)
        val updateOutcome = startupUpdater.installUpdate(remote) { state ->
            startupUpdateState = state
            startupStatusMessage = state.messageText()
        }

        when (updateOutcome) {
            StartupUpdateOutcome.ContinueToApp -> {
                updateChoiceRemote = null
                startupUpdateReleaseHistory = emptyList()
                startupActionLocked = false
                completeAppStartup()
            }

            is StartupUpdateOutcome.ContinueToAppWithError -> {
                startupActionLocked = false
                continueAfterUpdateFailure(updateOutcome.message)
            }

            is StartupUpdateOutcome.AwaitingUnknownSources -> {
                updateChoiceRemote = null
                startupUpdateReleaseHistory = emptyList()
                pendingInstallRemote = updateOutcome.remote
                awaitingUnknownSources = true
                startupStatusMessage = "Grant install permission to continue update..."
                openUnknownSourcesSettings(appContext)
            }

            is StartupUpdateOutcome.InstallerLaunched -> {
                updateChoiceRemote = null
                startupUpdateReleaseHistory = emptyList()
                pendingInstallRemote = updateOutcome.remote
                awaitingInstallerReturn = true
                startupStatusMessage = "Installer opened. Complete update to reload app."
            }
        }
    }

    suspend fun beginStartupUpdateFlow() {
        if (!BuildConfig.UPDATER_ENABLED) {
            startupUpdateState = StartupUpdateState.NoUpdate
            startupStatusMessage = "Loading app..."
            completeAppStartup()
            return
        }

        val remoteResult = startupUpdater.checkForEligibleUpdate { state ->
            startupUpdateState = state
            startupStatusMessage = state.messageText()
        }
        val eligibleRemote = remoteResult.getOrElse { error ->
            continueAfterUpdateFailure(error.message ?: "Could not check for updates")
            return
        }

        if (eligibleRemote == null) {
            startupUpdateReleaseHistory = emptyList()
            completeAppStartup()
            return
        }

        val installedVersionCode = installedAppVersionCode(appContext)
        val startupHistory = mergeReleaseHistory(
            remoteHistory = updateFeedClient.mainPrereleaseHistory(limit = 100).getOrElse { emptyList() },
            localCurrent = latestInstalledChangelog,
        )
            .filter { entry ->
                entry.versionCode > installedVersionCode && entry.versionCode <= eligibleRemote.versionCode
            }
            .sortedWith(releaseChangelogNewestFirstComparator())

        updateChoiceRemote = eligibleRemote
        startupUpdateReleaseHistory = if (startupHistory.isNotEmpty()) {
            startupHistory
        } else {
            listOf(eligibleRemote.toReleaseHistoryEntry())
        }
        startupUpdateState = StartupUpdateState.AwaitingUserChoice(eligibleRemote)
        startupStatusMessage = startupUpdateState.messageText()
    }

    LaunchedEffect(Unit) {
        beginStartupUpdateFlow()
    }

    LaunchedEffect(incomingUri) {
        val uri = incomingUri ?: return@LaunchedEffect
        if (pixivAuthController.isAuthorizationCallback(uri)) {
            val result = pixivAuthController.handleAuthorizationCallback(uri)
            if (result.isSuccess) {
                pixivStatusLabel = "Connected"
                refreshSourceAccountState()
            } else {
                pixivStatusLabel = "Connection failed: ${result.exceptionOrNull()?.message ?: "Unknown error"}"
            }
        } else {
            pendingPixivDeepLinkUri = uri
        }
        onIncomingUriConsumed()
    }

    LaunchedEffect(navReady, pendingPixivDeepLinkUri) {
        if (!navReady) return@LaunchedEffect
        val uri = pendingPixivDeepLinkUri ?: return@LaunchedEffect
        fun consumePendingUriIfCurrent() {
            if (pendingPixivDeepLinkUri == uri) {
                pendingPixivDeepLinkUri = null
            }
        }

        val postId = parsePixivPostIdFromUri(uri)
        if (postId == null) {
            Toast.makeText(appContext, "Unsupported Pixiv URL format", Toast.LENGTH_SHORT).show()
            consumePendingUriIfCurrent()
            return@LaunchedEffect
        }
        val adapter = realRegistry.adapterFor(SourceKey.PIXIV)
        if (adapter == null) {
            Toast.makeText(appContext, "Pixiv source is unavailable", Toast.LENGTH_SHORT).show()
            consumePendingUriIfCurrent()
            return@LaunchedEffect
        }

        val resolved = try {
            adapter.resolvePost(PostId(source = SourceKey.PIXIV, sourcePostId = postId))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val message = error.message ?: "Could not open Pixiv URL"
            Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
            consumePendingUriIfCurrent()
            return@LaunchedEffect
        }

        if (resolved == null) {
            Toast.makeText(appContext, "Pixiv post was not found", Toast.LENGTH_SHORT).show()
            consumePendingUriIfCurrent()
            return@LaunchedEffect
        }

        val context = ViewerLaunchContext(
            queryHash = "pixiv-deeplink:$postId",
            startIndex = 0,
            streamSource = ViewerStreamSource.SEARCH,
            scrollOffsetHint = 0,
        )
        viewerSession = ViewerSession(
            posts = listOf(resolved),
            context = context,
            liveSearchBinding = false,
        )
        uiRestoreRepository.setViewerLaunchContext(context)
        navController.navigate(AppRoute.Viewer) {
            launchSingleTop = true
        }
        consumePendingUriIfCurrent()
    }

    LaunchedEffect(settings) {
        val shouldRefresh = searchCoordinator.onSettingsChanged(settings)
        if (shouldRefresh) {
            searchCoordinator.retry()
        }
    }

    LaunchedEffect(codices.map { it.codexId }) {
        val activeIds = codices.map { it.codexId }.toSet()
        codexItemCounts.keys
            .filterNot { it in activeIds }
            .toList()
            .forEach { codexItemCounts.remove(it) }

        coroutineScope {
            codices.forEach { codex ->
                launch {
                    codexRepository.observeCodexItems(codex.codexId).collect { items ->
                        codexItemCounts[codex.codexId] = items.size
                    }
                }
            }
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val currentRoute = currentDestination?.route
    val topLevelRoutes = remember { TopLevelDestination.entries.map { it.route }.toSet() }
    val showBottomBar = currentRoute in topLevelRoutes
    val latestCurrentRoute = rememberUpdatedState(currentRoute)
    val currentContext = LocalContext.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val installedVersionCode = remember(appContext) { installedAppVersionCode(appContext) }
    val hostActivity = remember(currentContext) { currentContext.findActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val bottomBarHeightDp = remember(configuration.screenHeightDp) {
        (configuration.screenHeightDp * BOTTOM_BAR_HEIGHT_RATIO)
            .toInt()
            .coerceIn(MIN_BOTTOM_BAR_HEIGHT_DP, MAX_BOTTOM_BAR_HEIGHT_DP)
    }
    val bottomBarIconSizeDp = remember(bottomBarHeightDp) {
        (bottomBarHeightDp * BOTTOM_BAR_ICON_RATIO)
            .toInt()
            .coerceIn(MIN_BOTTOM_BAR_ICON_DP, MAX_BOTTOM_BAR_ICON_DP)
    }
    val bottomBarHeight = bottomBarHeightDp.dp
    val bottomBarIconSize = bottomBarIconSizeDp.dp
    val tabSwipeThresholdPx = with(density) { TAB_SWIPE_THRESHOLD_DP.dp.toPx() }

    LaunchedEffect(currentRoute) {
        if (currentRoute in TopLevelDestination.entries.map { it.route }) {
            val route = requireNotNull(currentRoute)
            settingsRepository.setLastTab(route)
            uiRestoreRepository.setLastTab(route)
        }
    }

    DisposableEffect(hostActivity, currentRoute) {
        hostActivity?.requestedOrientation = if (currentRoute == AppRoute.Viewer) {
            ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        onDispose {
            hostActivity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    DisposableEffect(
        lifecycleOwner,
        awaitingUnknownSources,
        awaitingInstallerReturn,
        pendingInstallRemote,
        navReady,
    ) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME || navReady) return@LifecycleEventObserver

            val remote = pendingInstallRemote
            if (awaitingUnknownSources && remote != null) {
                scope.launch {
                    val outcome = startupUpdater.retryPendingInstall(remote) { state ->
                        startupUpdateState = state
                        startupStatusMessage = state.messageText()
                    }
                    when (outcome) {
                        StartupUpdateOutcome.ContinueToApp -> {
                            awaitingUnknownSources = false
                            pendingInstallRemote = null
                            startupActionLocked = false
                            completeAppStartup()
                        }

                        is StartupUpdateOutcome.ContinueToAppWithError -> {
                            awaitingUnknownSources = false
                            pendingInstallRemote = null
                            startupActionLocked = false
                            continueAfterUpdateFailure(outcome.message)
                        }

                        is StartupUpdateOutcome.AwaitingUnknownSources -> {
                            awaitingUnknownSources = false
                            pendingInstallRemote = null
                            startupActionLocked = false
                            continueAfterUpdateFailure(
                                "Install permission was not granted. Continuing with current app.",
                            )
                        }

                        is StartupUpdateOutcome.InstallerLaunched -> {
                            awaitingUnknownSources = false
                            awaitingInstallerReturn = true
                            pendingInstallRemote = outcome.remote
                            startupActionLocked = true
                            startupStatusMessage = "Installer opened. Complete update to reload app."
                        }
                    }
                }
            } else if (awaitingInstallerReturn && remote != null) {
                scope.launch {
                    val installedNow = installedAppVersionCode(appContext)
                    if (installedNow >= remote.versionCode) {
                        awaitingInstallerReturn = false
                        pendingInstallRemote = null
                        startupActionLocked = false
                        startupUpdateState = StartupUpdateState.NoUpdate
                        startupStatusMessage = "Update installed. Loading app..."
                        completeAppStartup()
                    } else {
                        startupStatusMessage = "Installer opened. Complete update to continue, or continue without updating."
                    }
                }
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(
        searchCoordinator.results,
        searchCoordinator.appliedQueryHash,
        viewerSession?.context?.streamSource,
        viewerSession?.liveSearchBinding,
        viewerSession?.searchAnimatedOnly,
    ) {
        val session = viewerSession ?: return@LaunchedEffect
        if (!session.liveSearchBinding) return@LaunchedEffect
        if (session.context.streamSource != ViewerStreamSource.SEARCH) return@LaunchedEffect
        if (session.context.queryHash != searchCoordinator.appliedQueryHash) return@LaunchedEffect

        val incomingForViewer = if (session.searchAnimatedOnly) {
            searchCoordinator.results.filter(::isAnimatedPost)
        } else {
            searchCoordinator.results
        }
        val merged = mergeViewerPosts(session.posts, incomingForViewer)
        if (merged.size != session.posts.size) {
            viewerSession = session.copy(posts = merged)
        }
    }

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
                        onYes = {
                            if (startupActionLocked) return@StartupUpdatePromptCard
                            scope.launch {
                                performStartupInstall(promptRemote)
                            }
                        },
                        onNo = {
                            if (startupActionLocked) return@StartupUpdatePromptCard
                            scope.launch {
                                startupActionLocked = true
                                startupUpdater.onUserChoseNo(promptRemote)
                                updateChoiceRemote = null
                                startupUpdateReleaseHistory = emptyList()
                                startupUpdateState = StartupUpdateState.NoUpdate
                                startupStatusMessage = "Update skipped. Loading app..."
                                startupActionLocked = false
                                completeAppStartup()
                            }
                        },
                        onRemindLater = {
                            if (startupActionLocked) return@StartupUpdatePromptCard
                            scope.launch {
                                startupActionLocked = true
                                startupUpdater.onUserChoseRemindLater(promptRemote)
                                updateChoiceRemote = null
                                startupUpdateReleaseHistory = emptyList()
                                startupUpdateState = StartupUpdateState.NoUpdate
                                startupStatusMessage = "Update postponed for 24 hours. Loading app..."
                                startupActionLocked = false
                                completeAppStartup()
                            }
                        },
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
                            onClick = {
                                scope.launch {
                                    awaitingInstallerReturn = false
                                    pendingInstallRemote = null
                                    startupActionLocked = false
                                    continueAfterUpdateFailure(
                                        "Update was not completed. Continuing with current app.",
                                    )
                                }
                            },
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
            Scaffold(
                bottomBar = {
                    if (showBottomBar) {
                        NavigationBar(
                            modifier = Modifier.height(bottomBarHeight),
                        ) {
                            TopLevelDestination.entries.forEach { destination ->
                                val selected = currentDestination?.hierarchy?.any { it.route == destination.route } == true
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = {
                                        navController.navigate(destination.route) {
                                            popUpTo(navController.graph.findStartDestination().id) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = {
                                        val icon = when (destination) {
                                            TopLevelDestination.Search -> Icons.Default.Search
                                            TopLevelDestination.Explore -> Icons.Default.Explore
                                            TopLevelDestination.Codex -> Icons.Default.Collections
                                            TopLevelDestination.Settings -> Icons.Default.Settings
                                        }
                                        Icon(
                                            imageVector = icon,
                                            contentDescription = destination.label,
                                            modifier = Modifier.size(bottomBarIconSize),
                                        )
                                    },
                                    label = null,
                                    alwaysShowLabel = false,
                                )
                            }
                        }
                    }
                },
            ) { innerPadding ->
                NavHost(
                    navController = navController,
                    startDestination = startDestination,
                    modifier = Modifier
                        .padding(innerPadding)
                        .pointerInput(showBottomBar, tabSwipeThresholdPx) {
                            if (!showBottomBar) return@pointerInput
                            awaitEachGesture {
                                val firstDown = awaitFirstDown(
                                    requireUnconsumed = false,
                                    pass = PointerEventPass.Initial,
                                )
                                val pointerId = firstDown.id
                                var totalHorizontalDrag = 0f
                                var totalVerticalDrag = 0f

                                while (true) {
                                    val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                                    val change = event.changes.firstOrNull { it.id == pointerId }
                                        ?: event.changes.firstOrNull()
                                        ?: break
                                    val delta = change.positionChangeIgnoreConsumed()
                                    totalHorizontalDrag += delta.x
                                    totalVerticalDrag += delta.y
                                    if (!change.pressed) break
                                }

                                if (abs(totalHorizontalDrag) < tabSwipeThresholdPx) return@awaitEachGesture
                                if (abs(totalHorizontalDrag) <= abs(totalVerticalDrag) * TAB_SWIPE_HORIZONTAL_BIAS) {
                                    return@awaitEachGesture
                                }
                                val activeRoute = latestCurrentRoute.value ?: return@awaitEachGesture
                                val currentTabIndex = TopLevelDestination.entries.indexOfFirst { tab ->
                                    tab.route == activeRoute
                                }
                                if (currentTabIndex == -1) return@awaitEachGesture

                                val targetTabIndex = if (totalHorizontalDrag < 0f) {
                                    currentTabIndex + 1
                                } else {
                                    currentTabIndex - 1
                                }
                                val destination = TopLevelDestination.entries.getOrNull(targetTabIndex)
                                    ?: return@awaitEachGesture
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                ) {
                    composable(TopLevelDestination.Search.route) {
                        SearchScreen(
                            coordinator = searchCoordinator,
                            pixivUgoiraClient = pixivUgoiraClient,
                            onOpenViewer = { posts, context, animatedOnly ->
                                viewerSession = ViewerSession(
                                    posts = posts,
                                    context = context,
                                    liveSearchBinding = true,
                                    searchAnimatedOnly = animatedOnly,
                                )
                                scope.launch { searchCoordinator.setViewerLaunchContext(context) }
                                navController.navigate(AppRoute.Viewer)
                            },
                            onRequestSaveToCodex = { post ->
                                pendingSavePost = post
                                showSaveSheet = true
                            },
                            onSaveToDevice = { post ->
                                requestSaveToDevice(post)
                            },
                            onApplySearch = {
                                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                    searchCoordinator.applyDraft()
                                }
                            },
                            onRetrySearch = {
                                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                    searchCoordinator.retry()
                                }
                            },
                        )
                    }
                    composable(TopLevelDestination.Explore.route) {
                        ExploreScreen(
                            coordinator = searchCoordinator,
                            onApplyDraftAndNavigateToSearch = {
                                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                    searchCoordinator.applyDraft()
                                }
                                navController.navigate(TopLevelDestination.Search.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                        )
                    }
                    composable(TopLevelDestination.Codex.route) {
                        CodexListScreen(
                            codices = codices,
                            itemCounts = codexItemCounts,
                            onOpenCodex = { codexId ->
                                navController.navigate(AppRoute.codexDetail(codexId))
                            },
                            onCreateCodex = { name ->
                                scope.launch { codexRepository.createCodex(name) }
                            },
                            onRenameCodex = { codexId, name ->
                                scope.launch { codexRepository.renameCodex(codexId, name) }
                            },
                            onDeleteCodex = { codexId ->
                                scope.launch { codexRepository.deleteCodex(codexId) }
                            },
                        )
                    }
                    composable(
                        route = AppRoute.CodexDetail,
                        arguments = listOf(navArgument("codexId") { type = NavType.StringType }),
                    ) { entry ->
                        val codexId = requireNotNull(entry.arguments?.getString("codexId"))
                        var sortMode by rememberSaveable(codexId) { mutableStateOf(CodexSortMode.NEWEST_SAVED) }
                        val codex by codexRepository.observeCodex(codexId).collectAsState(initial = null)
                        val posts by codexRepository.observeCodexPosts(codexId, sortMode).collectAsState(initial = emptyList())

                        CodexDetailScreen(
                            codexName = codex?.name,
                            posts = posts,
                            sortMode = sortMode,
                            pixivUgoiraClient = pixivUgoiraClient,
                            onSortChange = { sortMode = it },
                            onOpenViewer = { index ->
                                val context = ViewerLaunchContext(
                                    queryHash = "codex:$codexId",
                                    startIndex = index,
                                    streamSource = ViewerStreamSource.CODEX,
                                    scrollOffsetHint = 0,
                                )
                                viewerSession = ViewerSession(posts = posts, context = context, liveSearchBinding = false)
                                scope.launch { uiRestoreRepository.setViewerLaunchContext(context) }
                                navController.navigate(AppRoute.Viewer)
                            },
                            onRemovePost = { post ->
                                scope.launch {
                                    codexRepository.removeItem(
                                        codexId = codexId,
                                        sourceKey = post.id.source,
                                        sourcePostId = post.id.sourcePostId,
                                    )
                                }
                            },
                            onSavePostToDevice = { post ->
                                requestSaveToDevice(post)
                            },
                            onBack = {
                                navController.popBackStack()
                            },
                            onDeleteCodex = {
                                scope.launch {
                                    codexRepository.deleteCodex(codexId)
                                    navController.popBackStack(TopLevelDestination.Codex.route, inclusive = false)
                                }
                            },
                        )
                    }
                    composable(TopLevelDestination.Settings.route) {
                        SettingsScreen(
                            settings = settings,
                            availableSources = searchCoordinator.availableSources,
                            cacheSnapshot = cacheSnapshot,
                            showDeveloperScenarios = false,
                            pixivStatusLabel = pixivStatusLabel,
                            pixivConnectEnabled = !pixivConnected &&
                                !pixivStatusLabel.startsWith("Awaiting authorization callback"),
                            onPixivConnect = {
                                val authUrl = pixivAuthController.startAuthorizationUri().toString()
                                pixivStatusLabel = "Awaiting authorization callback..."
                                pixivConnected = false
                                openInBrowser(appContext, authUrl)
                            },
                            onPixivDisconnect = {
                                scope.launch {
                                    credentialsStore.clearPixivTokens()
                                    refreshSourceAccountState()
                                }
                            },
                            gelbooruUserId = gelbooruUserIdInput,
                            gelbooruApiKey = gelbooruApiKeyInput,
                            gelbooruStatusLabel = gelbooruStatusLabel,
                            onGelbooruUserIdChange = { gelbooruUserIdInput = it.trim() },
                            onGelbooruApiKeyChange = { input ->
                                val parsed = parseGelbooruCredentialInput(input)
                                if (parsed != null) {
                                    gelbooruApiKeyInput = parsed.apiKey
                                    gelbooruUserIdInput = parsed.userId
                                } else {
                                    gelbooruApiKeyInput = input.trim()
                                }
                            },
                            onSaveGelbooruCredentials = {
                                scope.launch {
                                    if (gelbooruUserIdInput.isBlank() || gelbooruApiKeyInput.isBlank()) {
                                        gelbooruStatusLabel = "Missing user ID or API key"
                                    } else {
                                        credentialsStore.saveGelbooruCredentials(
                                            GelbooruCredentials(
                                                userId = gelbooruUserIdInput,
                                                apiKey = gelbooruApiKeyInput,
                                            )
                                        )
                                        refreshSourceAccountState()
                                        gelbooruStatusLabel = "Configured"
                                    }
                                }
                            },
                            onClearGelbooruCredentials = {
                                scope.launch {
                                    credentialsStore.clearGelbooruCredentials()
                                    refreshSourceAccountState()
                                }
                            },
                            onSetEnabledSources = { enabled ->
                                scope.launch {
                                    settingsRepository.setEnabledSources(
                                        enabled.intersect(searchCoordinator.availableSources.toSet())
                                    )
                                }
                            },
                            onSetSourceWeights = { weights ->
                                scope.launch { settingsRepository.setSourceWeights(weights) }
                            },
                            onSetCacheFullImageOnSave = { enabled ->
                                scope.launch { settingsRepository.setCacheFullImageOnSave(enabled) }
                            },
                            onSetScenarioPreset = { preset ->
                                scope.launch { settingsRepository.setScenarioPreset(preset) }
                            },
                            onClearThumbnailCache = {
                                scope.launch { cacheRepository.clearThumbnailCache() }
                            },
                            onClearFullImageCache = {
                                scope.launch { cacheRepository.clearFullImageCache() }
                            },
                            changelogLoading = releaseHistoryLoading,
                            onOpenChangelog = {
                                if (releaseHistoryLoading) return@SettingsScreen
                                scope.launch {
                                    releaseHistoryLoading = true
                                    val remoteHistory = updateFeedClient.mainPrereleaseHistory(limit = 50)
                                        .getOrElse { emptyList() }
                                    val merged = mergeReleaseHistory(
                                        remoteHistory = remoteHistory,
                                        localCurrent = latestInstalledChangelog,
                                    )
                                    if (merged.isEmpty()) {
                                        Toast.makeText(
                                            appContext,
                                            "No changelog available yet",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    } else {
                                        releaseHistoryEntries = merged
                                    }
                                    releaseHistoryLoading = false
                                }
                            },
                        )
                    }
                    composable(AppRoute.Viewer) {
                        val session = viewerSession
                        if (session == null) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("No viewer session")
                            }
                        } else {
                            ViewerScreen(
                                posts = session.posts,
                                launchContext = session.context,
                                pixivUgoiraClient = pixivUgoiraClient,
                                canLoadMoreFromSource = session.liveSearchBinding && searchCoordinator.canLoadMore,
                                loadingMoreFromSource = searchCoordinator.loadingMore,
                                onLoadMoreFromSource = if (session.liveSearchBinding) {
                                    { scope.launch { searchCoordinator.loadNextPage() } }
                                } else {
                                    null
                                },
                                onDismiss = {
                                    scope.launch { searchCoordinator.setViewerLaunchContext(null) }
                                    navController.popBackStack()
                                },
                                onSave = { post ->
                                    pendingSavePost = post
                                    showSaveSheet = true
                                },
                                onOpenInBrowser = { post ->
                                    post.pageUrl?.takeIf { it.isNotBlank() }?.let { url ->
                                        openInBrowser(appContext, url)
                                    }
                                },
                                onAddIncludeTag = { tag ->
                                    searchCoordinator.addIncludeTag(tag)
                                },
                                onAddExcludeTag = { tag ->
                                    searchCoordinator.addExcludeTag(tag)
                                },
                                onRemoveIncludeTag = { tag ->
                                    searchCoordinator.removeIncludeTag(tag)
                                },
                                onRemoveExcludeTag = { tag ->
                                    searchCoordinator.removeExcludeTag(tag)
                                },
                                onGoToSearch = {
                                    viewerSession = null
                                    scope.launch { searchCoordinator.setViewerLaunchContext(null) }
                                    navController.popBackStack(AppRoute.Viewer, inclusive = true)
                                    navController.navigate(TopLevelDestination.Search.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        if (showSaveSheet && pendingSavePost != null) {
            val post = requireNotNull(pendingSavePost)
            SaveToCodexSheet(
                codices = codices,
                onCreateCodex = { name ->
                    scope.launch { codexRepository.createCodex(name) }
                },
                onSelectCodex = { codexId ->
                    scope.launch {
                        codexRepository.addItem(codexId, post)
                        cacheRepository.cacheThumbnail(post)
                        if (settings.cache.cacheFullImageOnSave) {
                            cacheRepository.cacheFull(post)
                        }
                    }
                    showSaveSheet = false
                    pendingSavePost = null
                },
                onDismiss = {
                    showSaveSheet = false
                    pendingSavePost = null
                },
            )
        }

        postInstallChangelog?.let { changelog ->
            PostInstallChangelogDialog(
                releases = if (postInstallReleaseHistory.isNotEmpty()) {
                    postInstallReleaseHistory
                } else {
                    listOf(changelog.toReleaseHistoryEntry())
                },
                installedVersionCode = installedVersionCode,
                onDismiss = {
                    postInstallChangelog = null
                    postInstallReleaseHistory = emptyList()
                    scope.launch {
                        updateStateStore.setPendingPostInstallChangelog(null)
                    }
                },
            )
        }

        releaseHistoryEntries?.let { entries ->
            ReleaseHistoryDialog(
                releases = entries,
                installedVersionCode = installedVersionCode,
                onDismiss = { releaseHistoryEntries = null },
            )
        }
    }
}

@Composable
private fun StartupUpdatePromptCard(
    releases: List<ReleaseChangelogEntry>,
    installedVersionCode: Int,
    actionEnabled: Boolean,
    onYes: () -> Unit,
    onNo: () -> Unit,
    onRemindLater: () -> Unit,
) {
    val latestRelease = releases.firstOrNull()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 560.dp)
            .padding(horizontal = 16.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Update available",
                style = MaterialTheme.typography.titleLarge,
            )
            if (latestRelease != null) {
                val subtitleParts = buildList {
                    add(releaseDisplayTitle(latestRelease.releaseName, latestRelease.versionCode))
                    add("vc${latestRelease.versionCode}")
                    add(latestRelease.commitShaShort)
                }
                Text(
                    text = subtitleParts.joinToString(separator = " • "),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (releases.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    releases.forEachIndexed { index, release ->
                        val titleBase = releaseDisplayTitle(release.releaseName, release.versionCode)
                        val title = if (release.versionCode == installedVersionCode) {
                            "$titleBase (Current)"
                        } else {
                            titleBase
                        }
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        val sections = release.changelogSections.filter { section ->
                            section.bullets.isNotEmpty()
                        }
                        if (sections.isNotEmpty()) {
                            sections.forEach { section ->
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = section.title,
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    section.bullets.forEach { bullet ->
                                        ChangelogBulletText(bullet = bullet)
                                    }
                                }
                            }
                        } else {
                            Text(
                                text = firstChangelogLine(release.changelogMarkdown)
                                    ?: "No changelog details were published for this build.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (index != releases.lastIndex) {
                            HorizontalDivider()
                        }
                    }
                }
            } else {
                Text(
                    text = "No changelog details were published for this build.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onYes,
                    enabled = actionEnabled,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Yes")
                }
                TextButton(
                    onClick = onNo,
                    enabled = actionEnabled,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("No")
                }
            }
            TextButton(
                onClick = onRemindLater,
                enabled = actionEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Remind Later")
            }
        }
    }
}

@Composable
private fun PostInstallChangelogDialog(
    releases: List<ReleaseChangelogEntry>,
    installedVersionCode: Int,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Got it")
            }
        },
        title = {
            Text("What's new")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                releases.forEachIndexed { index, release ->
                    val titleBase = releaseDisplayTitle(release.releaseName, release.versionCode)
                    val title = if (release.versionCode == installedVersionCode) {
                        "$titleBase (Current)"
                    } else {
                        titleBase
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    val sections = release.changelogSections.filter { it.bullets.isNotEmpty() }
                    if (sections.isNotEmpty()) {
                        sections.forEach { section ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = section.title,
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                section.bullets.forEach { bullet ->
                                    ChangelogBulletText(bullet = bullet)
                                }
                            }
                        }
                    } else {
                        Text(
                            text = firstChangelogLine(release.changelogMarkdown)
                                ?: "No changelog details were published for this build.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (index != releases.lastIndex) {
                        HorizontalDivider()
                    }
                }
            }
        },
    )
}

@Composable
private fun ReleaseHistoryDialog(
    releases: List<ReleaseChangelogEntry>,
    installedVersionCode: Int,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        title = {
            Text("Release changelog")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                releases.forEachIndexed { index, release ->
                    val titleBase = releaseDisplayTitle(release.releaseName, release.versionCode)
                    val title = if (release.versionCode == installedVersionCode) {
                        "$titleBase (Current)"
                    } else {
                        titleBase
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    val sections = release.changelogSections.filter { it.bullets.isNotEmpty() }
                    if (sections.isNotEmpty()) {
                        sections.forEach { section ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = section.title,
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                section.bullets.forEach { bullet ->
                                    ChangelogBulletText(bullet = bullet)
                                }
                            }
                        }
                    } else {
                        Text(
                            text = firstChangelogLine(release.changelogMarkdown)
                                ?: "No changelog details were published for this build.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (index != releases.lastIndex) {
                        HorizontalDivider()
                    }
                }
            }
        },
    )
}

private fun openInBrowser(context: Context, url: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

private fun parsePixivPostIdFromUri(uri: Uri): String? {
    val scheme = uri.scheme?.lowercase().orEmpty()
    val host = uri.host?.lowercase().orEmpty()
    if (scheme != "https" && scheme != "http") return null
    if (host != "www.pixiv.com" && host != "pixiv.com" && host != "www.pixiv.net" && host != "pixiv.net") return null
    val path = uri.encodedPath.orEmpty()
    val match = Regex("^/([A-Za-z]{2})/artworks/(\\d+)(?:/)?$").matchEntire(path) ?: return null
    return match.groupValues.getOrNull(2)?.takeIf(String::isDigitsOnly)
}

private fun String.isDigitsOnly(): Boolean {
    return isNotBlank() && all { it.isDigit() }
}

@Composable
private fun ChangelogBulletText(bullet: String) {
    val leadingSpaces = bullet.takeWhile { it == ' ' }.length
    val indentLevel = (leadingSpaces / 2).coerceAtLeast(0)
    val normalized = bullet.trimStart()
    Text(
        text = "• $normalized",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(start = (indentLevel * 14).dp),
    )
}

private fun openUnknownSourcesSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    runCatching {
        val intent = Intent(
            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun installedAppVersionCode(context: Context): Int {
    val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(0),
        )
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0)
    }
    return PackageInfoCompat.getLongVersionCode(packageInfo).toInt()
}

private fun mergeReleaseHistory(
    remoteHistory: List<RemoteUpdate>,
    localCurrent: PendingPostInstallChangelog?,
): List<ReleaseChangelogEntry> {
    val entries = remoteHistory.map { remote ->
        ReleaseChangelogEntry(
            releaseId = remote.releaseId,
            versionCode = remote.versionCode,
            commitShaShort = remote.commitShaShort,
            releaseName = remote.releaseName,
            publishedAtEpochMs = remote.publishedAtEpochMs,
            changelogMarkdown = remote.changelogMarkdown,
            changelogSections = remote.changelogSections,
        )
    }.toMutableList()

    localCurrent?.let { local ->
        val alreadyPresent = entries.any { entry ->
            entry.releaseId == local.releaseId ||
                (entry.versionCode == local.versionCode && entry.commitShaShort == local.commitShaShort)
        }
        if (!alreadyPresent) {
            entries += ReleaseChangelogEntry(
                releaseId = local.releaseId,
                versionCode = local.versionCode,
                commitShaShort = local.commitShaShort,
                releaseName = local.releaseName,
                publishedAtEpochMs = null,
                changelogMarkdown = local.changelogMarkdown,
                changelogSections = local.changelogSections,
            )
        }
    }

    return entries.sortedWith(releaseChangelogNewestFirstComparator())
}

private fun PendingPostInstallChangelog.toReleaseHistoryEntry(): ReleaseChangelogEntry {
    return ReleaseChangelogEntry(
        releaseId = releaseId,
        versionCode = versionCode,
        commitShaShort = commitShaShort,
        releaseName = releaseName,
        publishedAtEpochMs = null,
        changelogMarkdown = changelogMarkdown,
        changelogSections = changelogSections,
    )
}

private fun RemoteUpdate.toReleaseHistoryEntry(): ReleaseChangelogEntry {
    return ReleaseChangelogEntry(
        releaseId = releaseId,
        versionCode = versionCode,
        commitShaShort = commitShaShort,
        releaseName = releaseName,
        publishedAtEpochMs = publishedAtEpochMs,
        changelogMarkdown = changelogMarkdown,
        changelogSections = changelogSections,
    )
}

private fun releaseChangelogNewestFirstComparator(): Comparator<ReleaseChangelogEntry> {
    return compareByDescending<ReleaseChangelogEntry> { it.publishedAtEpochMs ?: Long.MIN_VALUE }
        .thenByDescending { it.versionCode }
}

private fun releaseDisplayTitle(releaseName: String?, versionCode: Int): String {
    val normalized = releaseName?.trim().orEmpty()
    if (normalized.isNotBlank()) return normalized
    return "vc$versionCode"
}

private fun firstChangelogLine(markdown: String): String? {
    return markdown
        .lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
}

private fun enqueuePostDownload(context: Context, post: Post): Boolean {
    val media = buildList {
        addAll(post.media)
        post.full?.let { add(it) }
        add(post.preview)
    }.firstOrNull { ref ->
        !ref.url.isNullOrBlank()
    } ?: return false

    val url = media.url ?: return false
    val request = DownloadManager.Request(Uri.parse(url))
        .setAllowedOverMetered(true)
        .setAllowedOverRoaming(true)
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setMimeType(media.mime)
    when (post.id.source) {
        SourceKey.PIXIV -> {
            request
                .addRequestHeader("Referer", "https://www.pixiv.net/")
                .addRequestHeader("User-Agent", "Mozilla/5.0")
        }

        SourceKey.GELBOORU -> {
            request
                .addRequestHeader("Referer", "https://gelbooru.com/")
                .addRequestHeader("User-Agent", "Mozilla/5.0")
        }

        SourceKey.AIBOORU -> {
            request
                .addRequestHeader("Referer", "https://aibooru.online/")
                .addRequestHeader("User-Agent", "Mozilla/5.0")
        }
    }

    val guessedName = URLUtil.guessFileName(url, null, media.mime)
    val extension = guessedName.substringAfterLast('.', "")
    val base = post.title
        ?.trim()
        ?.replace(Regex("[^A-Za-z0-9._-]+"), "_")
        ?.trim('_')
        ?.takeIf { it.isNotBlank() }
        ?: "${post.id.source.name.lowercase()}_${post.id.sourcePostId}"
    val fileName = if (extension.isNotBlank()) "$base.$extension" else base
    request.setTitle(fileName)
    request.setDescription(post.pageUrl ?: "Saved from Theoria Codex")
    runCatching {
        request.setDestinationInExternalPublicDir(
            Environment.DIRECTORY_DOWNLOADS,
            "TheoriaCodex/$fileName",
        )
    }.onFailure {
        request.setDestinationInExternalFilesDir(
            context,
            Environment.DIRECTORY_DOWNLOADS,
            fileName,
        )
    }

    val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return false
    return runCatching {
        manager.enqueue(request)
        true
    }.getOrElse { false }
}

private fun mergeViewerPosts(current: List<Post>, incoming: List<Post>): List<Post> {
    if (incoming.isEmpty()) return current
    if (current.isEmpty()) return incoming
    val seen = current
        .mapTo(mutableSetOf()) { post -> "${post.id.source.name}:${post.id.sourcePostId}" }
    val merged = current.toMutableList()
    incoming.forEach { post ->
        val key = "${post.id.source.name}:${post.id.sourcePostId}"
        if (seen.add(key)) {
            merged += post
        }
    }
    return merged
}

private fun loadSeedTagSuggestions(context: Context): Map<SourceKey, List<TagSuggestion>> {
    val body = runCatching {
        context.assets.open("tag_store.json").bufferedReader().use { it.readText() }
    }.getOrNull() ?: return emptyMap()
    val root = runCatching { Gson().fromJson(body, JsonObject::class.java) }.getOrNull()
        ?: return emptyMap()
    val sources = root.getAsJsonObject("sources") ?: return emptyMap()
    return sources.entrySet().mapNotNull outer@{ (sourceName, value) ->
        val source = runCatching { SourceKey.valueOf(sourceName) }.getOrNull() ?: return@outer null
        val tags = value.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull inner@{ element ->
                val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@inner null
                val text = obj.get("text")
                    ?.takeUnless { it.isJsonNull }
                    ?.asString
                    ?.trim()
                    .orEmpty()
                if (text.isBlank()) return@inner null
                TagSuggestion(
                    text = text,
                    type = obj.get("type")
                        ?.takeUnless { it.isJsonNull }
                        ?.asString,
                    count = obj.get("count")
                        ?.takeUnless { it.isJsonNull }
                        ?.asInt,
                )
            }
            .orEmpty()
        source to tags
    }.toMap()
}

private const val PIXIV_TOKEN_REFRESH_TIMEOUT_MS = 6_000L
private const val BOTTOM_BAR_HEIGHT_RATIO = 0.085f
private const val BOTTOM_BAR_ICON_RATIO = 0.38f
private const val MIN_BOTTOM_BAR_HEIGHT_DP = 68
private const val MAX_BOTTOM_BAR_HEIGHT_DP = 88
private const val MIN_BOTTOM_BAR_ICON_DP = 24
private const val MAX_BOTTOM_BAR_ICON_DP = 30
private const val TAB_SWIPE_THRESHOLD_DP = 72
private const val TAB_SWIPE_HORIZONTAL_BIAS = 1.2f
