package com.theoriacodex.app.ui

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.theoriacodex.app.media.isPixivUgoiraPost
import com.theoriacodex.app.media.PostDownloadService
import com.theoriacodex.app.media.normalizeMediaUrl
import com.theoriacodex.app.codex.CodexDetailScreen
import com.theoriacodex.app.codex.CodexListScreen
import com.theoriacodex.app.codex.CodexSearchSourceOption
import com.theoriacodex.app.codex.CodexSearchTagOption
import com.theoriacodex.app.codex.SaveToCodexSheet
import com.theoriacodex.app.codex.codexBelongsToProfile
import com.theoriacodex.app.codex.codexSearchSourceOptions as buildCodexSearchSourceOptions
import com.theoriacodex.app.codex.codexSearchTagOptions as buildCodexSearchTagOptions
import com.theoriacodex.app.codex.profileScopedCodexId
import com.theoriacodex.app.codex.PROFILE_CODEX_ID_PREFIX
import com.theoriacodex.app.codex.transfer.CodexExportResult
import com.theoriacodex.app.codex.transfer.CodexImportResult
import com.theoriacodex.app.appshell.AppShellAction
import com.theoriacodex.app.appshell.AppShellEffect
import com.theoriacodex.app.appshell.AppShellViewModel
import com.theoriacodex.app.appshell.IncomingUriKind
import com.theoriacodex.app.appshell.ViewerSessionRetentionViewModel
import com.theoriacodex.app.creator.browseableCreatorProfile
import com.theoriacodex.app.creator.state.CreatorAction
import com.theoriacodex.app.creator.state.CreatorUiState
import com.theoriacodex.app.di.TheoriaAppContainer
import com.theoriacodex.app.recommend.trainingTagsFor
import com.theoriacodex.app.recommend.state.ForYouUiState
import com.theoriacodex.app.recents.RecentsScreen
import com.theoriacodex.app.search.UnknownAnimatedDurationPolicy
import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.search.state.SearchUiState
import com.theoriacodex.app.source.ExternalCreatorDeepLink
import com.theoriacodex.app.source.ExternalPostDeepLink
import com.theoriacodex.app.settings.SettingsScreen
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.source.parseExternalCreatorDeepLink
import com.theoriacodex.app.source.parseExternalPostDeepLink
import com.theoriacodex.app.source.requestHeaders
import com.theoriacodex.app.sourceauth.CredentialStoreRecoveryState
import com.theoriacodex.app.sourceauth.parseGelbooruCredentialInput
import com.theoriacodex.app.sourceauth.parseRule34XxxCredentialInput
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
import com.theoriacodex.app.ui.routes.downloadViewerMediaMessage
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
import com.theoriacodex.app.ui.routes.PendingRouteActions
import com.theoriacodex.app.update.ChangelogSection
import com.theoriacodex.app.update.RemoteUpdate
import com.theoriacodex.app.update.PendingPostInstallChangelog
import com.theoriacodex.app.update.StartupUpdateOutcome
import com.theoriacodex.app.update.StartupUpdateState
import com.theoriacodex.app.update.StartupUpdateWorkflowEvent
import com.theoriacodex.app.update.StartupUpdateWorkflowEffect
import com.theoriacodex.app.update.UnknownSourcesPermissionRequiredException
import com.theoriacodex.app.viewer.ViewerSession
import com.theoriacodex.app.viewer.ViewerMediaPrefetcher
import com.theoriacodex.app.viewer.ViewerPostResolver
import com.theoriacodex.app.viewer.prefetchViewerMedia
import com.theoriacodex.app.viewer.requiresLazyMediaResolution
import com.theoriacodex.app.viewer.requiresViewerPostResolution
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.CacheSnapshot
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.data.repository.ForYouBlacklistEntry
import com.theoriacodex.data.repository.RecommendationProfile
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.credentials.GelbooruCredentials
import com.theoriacodex.sources.credentials.Rule34XxxCredentials
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle

enum class TopLevelDestination(val route: String, val label: String) {
    Search("search", "Search"),
    Recents("recents", "Recents"),
    ForYou("for_you", "For You"),
    Codex("codex", "Codex"),
    Settings("settings", "Settings"),
}

private object AppRoute {
    const val Home = "home"
    const val CreatorProfile = "creator-profile"
    const val Viewer = "viewer"
    const val CodexDetail = "codex/detail/{codexId}"

    fun codexDetail(codexId: String): String {
        return "codex/detail/$codexId"
    }
}

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
    val appContext = LocalContext.current.applicationContext
    val viewerSessionOwner = viewModel<ViewerSessionRetentionViewModel>()
    val appShellOwner = viewModel<AppShellViewModel>()
    TheoriaAppContent(
        appContainer = rememberTheoriaAppContainer(appContext),
        viewerSessionOwner = viewerSessionOwner,
        appShellOwner = appShellOwner,
        incomingUri = incomingUri,
        onIncomingUriConsumed = onIncomingUriConsumed,
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
    val navController = rememberNavController()
    val appContext = LocalContext.current.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val dataDependencies = appContainer.data
    val sourceDependencies = appContainer.sources
    val updateDependencies = appContainer.updates
    val featureDependencies = appContainer.features
    val workflowDependencies = appContainer.workflows
    val availableRealSources by sourceDependencies.availableSources.collectAsStateWithLifecycle()
    val credentialRecoveryState by sourceDependencies.accounts.recoveryState.collectAsStateWithLifecycle()
    val appShellState by appShellOwner.state.collectAsStateWithLifecycle()

    val settings by dataDependencies.settingsRepository
        .observeSettings()
        .collectAsStateWithLifecycle(initialValue = AppSettings())
    val recentWatchedPosts by dataDependencies.recentsRepository
        .observeWatchedPosts()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val recentSearches by dataDependencies.recentsRepository
        .observeSearches()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val recentActivity by dataDependencies.recentsRepository
        .observeActivity()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val unknownAnimatedDurationPolicy = remember(settings.contentFilters.resolveUnknownAnimatedDurations) {
        if (settings.contentFilters.resolveUnknownAnimatedDurations) {
            UnknownAnimatedDurationPolicy.RESOLVE_IN_BACKGROUND
        } else {
            UnknownAnimatedDurationPolicy.HIDE_UNKNOWNS
        }
    }
    val activeRecommendationProfile = remember(settings.recommendationProfiles, settings.activeProfileId) {
        settings.recommendationProfiles
            .firstOrNull { it.profileId == settings.activeProfileId }
            ?: settings.recommendationProfiles.firstOrNull()
            ?: RecommendationProfile(profileId = "profile-main", name = "Main")
    }
    val likedPostIds by dataDependencies.likesRepository
        .observeLikedPostIds(activeRecommendationProfile.profileId)
        .collectAsStateWithLifecycle(initialValue = emptySet())
    val activeProfileLikes by dataDependencies.likesRepository
        .observeLikes(activeRecommendationProfile.profileId)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val activeProfileForYouBlacklist = remember(settings.forYouBlacklistByProfile, activeRecommendationProfile.profileId) {
        settings.forYouBlacklistByProfile[activeRecommendationProfile.profileId]
            .orEmpty()
            .sortedWith(
                compareBy<ForYouBlacklistEntry> { entry -> entry.source.name }
                    .thenBy { entry -> entry.tags.joinToString(separator = "+") }
            )
    }
    val activeProfileFavoriteTags = remember(settings.favoriteTagsByProfile, activeRecommendationProfile.profileId) {
        settings.favoriteTagsByProfile[activeRecommendationProfile.profileId]
            .orEmpty()
            .groupBy { entry -> entry.source }
            .mapValues { (_, entries) -> entries.map { entry -> entry.tag } }
    }
    val cacheSnapshot by dataDependencies.cacheRepository.observeSnapshot().collectAsStateWithLifecycle(
        initialValue = CacheSnapshot(thumbnailCount = 0, fullImageCount = 0),
    )
    val codices by dataDependencies.codexRepository
        .observeCodices()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val saveSheetCodicesByProfile = remember(codices, settings.recommendationProfiles) {
        settings.recommendationProfiles.associate { profile ->
            profile.profileId to codices.filter { codex ->
                codexBelongsToProfile(codex.codexId, profile.profileId)
            }
        }
    }
    val visibleCodices = remember(codices, activeRecommendationProfile.profileId) {
        codices.filter { codex ->
            codexBelongsToProfile(codex.codexId, activeRecommendationProfile.profileId)
        }
    }

    var searchRouteOwner by remember { mutableStateOf<SearchRouteOwnerHandle?>(null) }
    var forYouRouteOwner by remember { mutableStateOf<ForYouRouteOwnerHandle?>(null) }
    var creatorRouteOwner by remember { mutableStateOf<CreatorRouteOwnerHandle?>(null) }
    var pendingCreatorProfile by remember { mutableStateOf<CreatorProfile?>(null) }
    val pendingSearchActions = remember { PendingRouteActions<SearchAction>() }
    val emptySearchRouteState = remember { MutableStateFlow(SearchUiState()) }
    val emptyForYouRouteState = remember { MutableStateFlow(ForYouUiState()) }
    val emptyCreatorRouteState = remember { MutableStateFlow(CreatorUiState()) }
    val searchRouteState by (searchRouteOwner?.state ?: emptySearchRouteState)
        .collectAsStateWithLifecycle()
    val forYouRouteState by (forYouRouteOwner?.state ?: emptyForYouRouteState)
        .collectAsStateWithLifecycle()
    val creatorRouteState by (creatorRouteOwner?.state ?: emptyCreatorRouteState)
        .collectAsStateWithLifecycle()
    val viewerRouteWorkflow = ViewerRouteWorkflow(
        data = dataDependencies,
        sources = sourceDependencies,
        searchOwner = { searchRouteOwner },
        forYouOwner = { forYouRouteOwner },
        creatorOwner = { creatorRouteOwner },
    )
    var activeViewerOwner by remember { mutableStateOf<ViewerRouteOwnerHandle?>(null) }
    val emptyViewerSession = remember { MutableStateFlow<ViewerSession?>(null) }
    val retainedViewerSession by viewerSessionOwner.session
    val routeViewerSession by (activeViewerOwner?.session ?: emptyViewerSession)
        .collectAsStateWithLifecycle()
    val viewerSession = routeViewerSession ?: retainedViewerSession
    fun dispatchOrQueueSearchAction(action: SearchAction): Boolean {
        return pendingSearchActions.dispatchOrEnqueue(action) { queuedAction ->
            searchRouteOwner?.dispatch(queuedAction) == true
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
    var pendingSavePost by remember { mutableStateOf<Post?>(null) }
    var homeTabRoute by rememberSaveable { mutableStateOf(TopLevelDestination.Search.route) }
    var pendingTopLevelRoute by remember { mutableStateOf<String?>(null) }
    var homeTabRestoreComplete by remember { mutableStateOf(false) }
    var navReady by remember(appContainer) { mutableStateOf(appShellState.appReady) }
    val startupWorkflowState = appShellState.startup
    val startupUpdateState = startupWorkflowState.updateState
    val startupStatusMessage = startupWorkflowState.statusMessage
    val updateChoiceRemote = startupWorkflowState.choice
    var startupUpdateReleaseHistory by remember { mutableStateOf<List<ReleaseChangelogEntry>>(emptyList()) }
    var postInstallChangelog by remember { mutableStateOf<PendingPostInstallChangelog?>(null) }
    var postInstallReleaseHistory by remember { mutableStateOf<List<ReleaseChangelogEntry>>(emptyList()) }
    var latestInstalledChangelog by remember { mutableStateOf<PendingPostInstallChangelog?>(null) }
    var releaseHistoryEntries by remember { mutableStateOf<List<ReleaseChangelogEntry>?>(null) }
    var releaseHistoryLoading by remember { mutableStateOf(false) }
    val startupActionLocked = startupWorkflowState.actionLocked
    val pendingInstallRemote = startupWorkflowState.pendingInstall
    val awaitingUnknownSources = startupWorkflowState.awaitingUnknownSources
    val awaitingInstallerReturn = startupWorkflowState.awaitingInstallerReturn
    fun updateStartupWorkflow(event: StartupUpdateWorkflowEvent) {
        appShellOwner.onAction(AppShellAction.UpdateStartup(event))
    }
    val codexItemCounts = remember { mutableStateMapOf<String, Int>() }
    val codexCoverModels = remember { mutableStateMapOf<String, Any?>() }
    val codexSearchSourceOptions = remember { mutableStateMapOf<String, List<CodexSearchSourceOption>>() }
    val codexSearchTagOptions = remember { mutableStateMapOf<String, Map<SourceKey, List<CodexSearchTagOption>>>() }
    val savedPostIdsByCodex = remember { mutableStateMapOf<String, Set<PostId>>() }
    val savedPostIds by remember {
        derivedStateOf {
            savedPostIdsByCodex.values
                .asSequence()
                .flatten()
                .toSet()
        }
    }
    val addFavoriteTag: (SourceKey, String) -> Unit = remember(
        scope,
        dataDependencies.settingsRepository,
        activeRecommendationProfile.profileId,
        appContext,
    ) {
        { source, tag ->
            scope.launch {
                val added = dataDependencies.settingsRepository.addFavoriteTag(
                    profileId = activeRecommendationProfile.profileId,
                    source = source,
                    tag = tag,
                )
                val message = if (added) {
                    "Added to favorite tags"
                } else {
                    "Already in favorite tags"
                }
                Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
            }
        }
    }
    val removeFavoriteTag: (SourceKey, String) -> Unit = remember(
        scope,
        dataDependencies.settingsRepository,
        activeRecommendationProfile.profileId,
    ) {
        { source, tag ->
            scope.launch {
                dataDependencies.settingsRepository.removeFavoriteTag(
                    profileId = activeRecommendationProfile.profileId,
                    source = source,
                    tag = tag,
                )
            }
        }
    }

    var pixivStatusLabel by remember { mutableStateOf("Not connected") }
    var pixivConnected by remember { mutableStateOf(false) }
    var gelbooruStatusLabel by remember { mutableStateOf("Not configured") }
    var gelbooruUserIdInput by rememberSaveable { mutableStateOf("") }
    var gelbooruApiKeyInput by rememberSaveable { mutableStateOf("") }
    var rule34XxxStatusLabel by remember { mutableStateOf("Not configured") }
    var rule34XxxUserIdInput by rememberSaveable { mutableStateOf("") }
    var rule34XxxApiKeyInput by rememberSaveable { mutableStateOf("") }
    var showCredentialRecoveryDialog by rememberSaveable { mutableStateOf(false) }

    suspend fun refreshSourceAccountState() {
        if (sourceDependencies.accounts.recoveryState.value == CredentialStoreRecoveryState.ReconnectRequired) {
            pixivConnected = false
            pixivStatusLabel = CREDENTIAL_RECONNECT_MESSAGE
            gelbooruStatusLabel = CREDENTIAL_RECONNECT_MESSAGE
            gelbooruUserIdInput = ""
            gelbooruApiKeyInput = ""
            rule34XxxStatusLabel = CREDENTIAL_RECONNECT_MESSAGE
            rule34XxxUserIdInput = ""
            rule34XxxApiKeyInput = ""
            return
        }
        val pixivTokens = sourceDependencies.accounts.getPixivTokens()
        pixivStatusLabel = when {
            pixivTokens == null -> {
                pixivConnected = false
                "Not connected"
            }
            pixivTokens.expiresAtEpochMs <= System.currentTimeMillis() -> {
                pixivStatusLabel = "Connected (refreshing token...)"
                val refreshResult = withTimeoutOrNull(PIXIV_TOKEN_REFRESH_TIMEOUT_MS) {
                    runCatchingPreservingCancellation {
                        sourceDependencies.pixivAuthApi.refresh(pixivTokens.refreshToken)
                    }
                }
                when {
                    refreshResult == null -> {
                        pixivConnected = false
                        "Connected (refresh timed out, retry on next request)"
                    }
                    refreshResult.isSuccess -> {
                        sourceDependencies.accounts.savePixivTokens(requireNotNull(refreshResult.getOrNull()))
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
                            sourceDependencies.accounts.clearPixivTokens()
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

        val gelbooruCredentials = sourceDependencies.accounts.getGelbooruCredentials()
        if (gelbooruCredentials == null) {
            gelbooruStatusLabel = "Not configured"
            gelbooruUserIdInput = ""
            gelbooruApiKeyInput = ""
        } else {
            gelbooruStatusLabel = "Configured"
            gelbooruUserIdInput = gelbooruCredentials.userId
            gelbooruApiKeyInput = gelbooruCredentials.apiKey
        }

        val rule34XxxCredentials = sourceDependencies.accounts.getRule34XxxCredentials()
        if (rule34XxxCredentials == null) {
            rule34XxxStatusLabel = "Not configured"
            rule34XxxUserIdInput = ""
            rule34XxxApiKeyInput = ""
        } else {
            rule34XxxStatusLabel = "Configured"
            rule34XxxUserIdInput = rule34XxxCredentials.userId
            rule34XxxApiKeyInput = rule34XxxCredentials.apiKey
        }
    }

    LaunchedEffect(credentialRecoveryState) {
        if (credentialRecoveryState == CredentialStoreRecoveryState.ReconnectRequired) {
            showCredentialRecoveryDialog = true
            refreshSourceAccountState()
        }
    }

    fun requestSaveToDevice(post: Post) {
        scope.launch {
            val resultLabel = if (isPixivUgoiraPost(post)) {
                sourceDependencies.pixivUgoiraClient.exportToMp4(
                    context = appContext,
                    postId = post.id.sourcePostId,
                    title = post.title,
                ).fold(
                    onSuccess = { "Saved video to device" },
                    onFailure = { "Could not save video" },
                )
            } else {
                val postToDownload = when {
                    !requiresLazyMediaResolution(post) -> post
                    else -> {
                        val adapter = sourceDependencies.registry.adapterFor(post.id.source)
                        runCatchingPreservingCancellation {
                            adapter?.resolvePost(post.id)
                        }.getOrNull()?.also { resolved ->
                            dispatchOrQueueSearchAction(SearchAction.RememberResolvedPost(resolved))
                        }
                    }
                }
                if (postToDownload != null && PostDownloadService.enqueuePostDownload(appContext, postToDownload)) {
                    "Download queued"
                } else {
                    "Could not queue download"
                }
            }
            Toast.makeText(appContext, resultLabel, Toast.LENGTH_SHORT).show()
        }
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

    suspend fun openCreatorProfile(post: Post) {
        var resolvedPost = post
        var creator = post.creatorProfiles
            .asSequence()
            .mapNotNull(::browseableCreatorProfile)
            .firstOrNull()
            ?: browseableCreatorProfile(post.creatorProfile)
        if (creator == null) {
            val adapter = sourceDependencies.registry.adapterFor(post.id.source)
            val resolved = runCatchingPreservingCancellation {
                adapter?.resolvePost(post.id)
            }.getOrNull()
            if (resolved != null) {
                dispatchOrQueueSearchAction(SearchAction.RememberResolvedPost(resolved))
                resolvedPost = resolved
                creator = resolved.creatorProfiles
                    .asSequence()
                    .mapNotNull(::browseableCreatorProfile)
                    .firstOrNull()
                    ?: browseableCreatorProfile(resolved.creatorProfile)
            }
        }
        if (creator == null) {
            Toast.makeText(appContext, "Creator profile unavailable", Toast.LENGTH_SHORT).show()
            return
        }
        openCreatorProfile(creator)
        viewerSessionOwner.update { session ->
            val index = session.posts.indexOfFirst { current -> current.id == resolvedPost.id }
            if (index < 0) {
                session
            } else {
                session.copy(
                    posts = session.posts.toMutableList().apply {
                        this[index] = resolvedPost
                    },
                )
            }
        }
    }

    suspend fun resolveExternalCreatorDeepLink(deepLink: ExternalCreatorDeepLink): CreatorProfile? {
        return when (deepLink.source) {
            SourceKey.PIXIV -> CreatorProfile(
                source = SourceKey.PIXIV,
                displayName = "User ${deepLink.creatorId}",
                profileId = deepLink.creatorId,
                profileUrl = deepLink.profileUrl,
                uploadsQuery = deepLink.creatorId,
            )

            SourceKey.HITOMI -> CreatorProfile(
                source = SourceKey.HITOMI,
                displayName = deepLink.creatorId,
                profileId = deepLink.creatorId,
                profileUrl = deepLink.profileUrl,
                uploadsQuery = "artist:${deepLink.creatorId}",
            )

            SourceKey.GELBOORU -> {
                val response = runCatchingPreservingCancellation {
                    sourceDependencies.httpClient.get(
                        url = "https://gelbooru.com/index.php",
                        query = mapOf(
                            "page" to "account",
                            "s" to "profile",
                            "id" to deepLink.creatorId,
                        ),
                        headers = SourceKey.GELBOORU.requestHeaders(),
                    )
                }.getOrNull() ?: return null
                if (response.statusCode !in 200..299) return null
                val owner = parseGelbooruProfileOwner(response.body)
                browseableCreatorProfile(
                    CreatorProfile(
                        source = SourceKey.GELBOORU,
                        displayName = owner ?: deepLink.creatorId,
                        profileId = deepLink.creatorId,
                        profileUrl = deepLink.profileUrl,
                        uploadsQuery = owner?.let { "user:$it" },
                    ),
                )
            }

            else -> null
        }
    }

    suspend fun ensureLikesCodexId(profile: RecommendationProfile): String {
        return workflowDependencies.likesCodexSync.ensureProfileCodex(profile)
    }

    suspend fun toggleLikeAndSyncCodex(post: Post) {
        workflowDependencies.likesCodexSync.toggle(
            profile = activeRecommendationProfile,
            post = post,
            trainingTags = trainingTagsFor(post),
        )
    }

    suspend fun clearProfileLikesAndSyncCodex(profileId: String) {
        workflowDependencies.likesCodexSync.clearProfile(profileId)
    }

    suspend fun removeProfileLikesCodex(profileId: String) {
        workflowDependencies.likesCodexSync.removeProfileCodex(profileId)
    }

    suspend fun removeProfileScopedCodices(profileId: String) {
        val prefix = "${PROFILE_CODEX_ID_PREFIX}_${profileId}_"
        codices
            .filter { codex -> codex.codexId.startsWith(prefix) }
            .forEach { codex ->
                dataDependencies.codexRepository.deleteCodex(codex.codexId)
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
        homeTabRoute = TopLevelDestination.Search.route
        pendingTopLevelRoute = TopLevelDestination.Search.route
        navController.navigate(AppRoute.Home) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
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

        posts.forEach { post ->
            requestSaveToDevice(post)
        }
        Toast.makeText(
            appContext,
            "Downloading ${posts.size} posts from codex",
            Toast.LENGTH_SHORT,
        ).show()
    }

    suspend fun shareCodex(codexId: String) {
        val export = workflowDependencies.codexTransfer.export(codexId)
        if (export !is CodexExportResult.Success) {
            Toast.makeText(appContext, "Codex not found", Toast.LENGTH_SHORT).show()
            return
        }
        val payload = export.payload

        val exportsDirectory = dataDependencies.storageDirectory.resolve("exports").apply { mkdirs() }
        val exportFile = exportsDirectory.resolve(payload.fileName)
        runCatching {
            exportFile.writeText(payload.json)
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

    suspend fun importCodexFromUri(uri: Uri) {
        val raw = runCatching {
            appContext.contentResolver.openInputStream(uri)
                ?.bufferedReader()
                ?.use { it.readText() }
        }.getOrNull()
        if (raw.isNullOrBlank()) {
            Toast.makeText(appContext, "Could not read codex file", Toast.LENGTH_SHORT).show()
            return
        }

        refreshSourceAccountState()
        when (
            val result = workflowDependencies.codexTransfer.import(
                raw = raw,
                targetCodexId = profileScopedCodexId(activeRecommendationProfile.profileId),
            )
        ) {
            CodexImportResult.Unreadable -> {
                Toast.makeText(appContext, "Could not read codex file", Toast.LENGTH_SHORT).show()
            }
            CodexImportResult.Invalid -> {
                Toast.makeText(appContext, "Invalid codex file", Toast.LENGTH_SHORT).show()
            }
            is CodexImportResult.Success -> {
                val message = if (result.imported == 0 && result.skipped == 0) {
                    "Imported codex with no posts"
                } else {
                    "Imported ${result.imported} posts${if (result.skipped > 0) " (${result.skipped} skipped)" else ""}"
                }
                Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val importCodexLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                importCodexFromUri(uri)
            }
        },
    )

    suspend fun commitVisibleCodexOrder(orderedVisibleIds: List<String>) {
        if (orderedVisibleIds.isEmpty()) return
        val idSet = orderedVisibleIds.toSet()
        if (idSet.size != orderedVisibleIds.size) return

        val codexById = codices.associateBy { codex -> codex.codexId }
        if (!orderedVisibleIds.all { id -> codexById.containsKey(id) }) return

        val visibleSlots = codices.mapIndexedNotNull { index, codex ->
            index.takeIf { codex.codexId in idSet }
        }
        if (visibleSlots.size != orderedVisibleIds.size) return

        val desired = codices.toMutableList()
        orderedVisibleIds.forEachIndexed { orderIndex, codexId ->
            val slot = visibleSlots[orderIndex]
            desired[slot] = codexById.getValue(codexId)
        }
        desired.forEachIndexed { index, codex ->
            dataDependencies.codexRepository.reorderCodex(codex.codexId, index)
        }
    }

    suspend fun completeAppStartup() {
        if (navReady) return
        sourceDependencies.accounts.refreshAvailability()
        ensureLikesCodexId(activeRecommendationProfile)
        refreshSourceAccountState()
        val legacyLastTabRoute = dataDependencies.settingsRepository
            .observeSettings()
            .first()
            .lastSelectedTabRoute
        homeTabRoute = dataDependencies.uiRestoreRepository.migrateLegacyLastTab(legacyLastTabRoute)
            ?.takeIf { route -> TopLevelDestination.entries.any { destination -> destination.route == route } }
            ?: TopLevelDestination.Search.route
        appShellOwner.onAction(AppShellAction.MarkAppReady)
        navReady = true
        val updateSnapshot = updateDependencies.startupUpdater.pendingSnapshot()
        latestInstalledChangelog = updateSnapshot.lastInstalledChangelog
        val pendingChangelog = updateSnapshot.pendingPostInstallChangelog
        if (pendingChangelog != null) {
            val installedVersionCode = installedAppVersionCode(appContext)
            if (pendingChangelog.versionCode <= installedVersionCode) {
                postInstallChangelog = pendingChangelog
                latestInstalledChangelog = pendingChangelog
                val merged = mergeReleaseHistory(
                    remoteHistory = updateDependencies.feedClient.mainPrereleaseHistory(limit = 100)
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

    LaunchedEffect(appShellOwner) {
        appShellOwner.effects.collect { effect ->
            when (effect) {
                is AppShellEffect.Startup -> when (effect.effect) {
                    StartupUpdateWorkflowEffect.ContinueToApp -> completeAppStartup()
                    StartupUpdateWorkflowEffect.OpenUnknownSourcesSettings -> {
                        openUnknownSourcesSettings(appContext)
                    }
                    is StartupUpdateWorkflowEffect.InstallerOpened -> Unit
                }
            }
        }
    }

    suspend fun continueAfterUpdateFailure(message: String) {
        startupUpdateReleaseHistory = emptyList()
        updateStartupWorkflow(StartupUpdateWorkflowEvent.Failed(message))
        delay(1_200)
        completeAppStartup()
    }

    suspend fun performStartupInstall(remote: RemoteUpdate) {
        updateStartupWorkflow(StartupUpdateWorkflowEvent.InstallStarted)
        updateDependencies.startupUpdater.onUserChoseYes(remote)
        val updateOutcome = updateDependencies.startupUpdater.installUpdate(remote) { state ->
            updateStartupWorkflow(StartupUpdateWorkflowEvent.UpdaterStateChanged(state))
        }

        when (updateOutcome) {
            StartupUpdateOutcome.ContinueToApp -> {
                updateStartupWorkflow(StartupUpdateWorkflowEvent.InstallFinished(updateOutcome))
                startupUpdateReleaseHistory = emptyList()
            }

            is StartupUpdateOutcome.ContinueToAppWithError -> {
                continueAfterUpdateFailure(updateOutcome.message)
            }

            is StartupUpdateOutcome.AwaitingUnknownSources -> {
                updateStartupWorkflow(StartupUpdateWorkflowEvent.InstallFinished(updateOutcome))
                startupUpdateReleaseHistory = emptyList()
            }

            is StartupUpdateOutcome.InstallerLaunched -> {
                updateStartupWorkflow(StartupUpdateWorkflowEvent.InstallFinished(updateOutcome))
                startupUpdateReleaseHistory = emptyList()
            }
        }
    }

    suspend fun beginStartupUpdateFlow() {
        if (!BuildConfig.UPDATER_ENABLED) {
            updateStartupWorkflow(StartupUpdateWorkflowEvent.ContinuedToApp("Loading app..."))
            return
        }

        val remoteResult = updateDependencies.startupUpdater.checkForEligibleUpdate { state ->
            updateStartupWorkflow(StartupUpdateWorkflowEvent.UpdaterStateChanged(state))
        }
        val eligibleRemote = remoteResult.getOrElse { error ->
            continueAfterUpdateFailure(error.message ?: "Could not check for updates")
            return
        }

        if (eligibleRemote == null) {
            startupUpdateReleaseHistory = emptyList()
            updateStartupWorkflow(StartupUpdateWorkflowEvent.NoEligibleUpdate)
            return
        }

        val installedVersionCode = installedAppVersionCode(appContext)
        val startupHistory = mergeReleaseHistory(
            remoteHistory = updateDependencies.feedClient.mainPrereleaseHistory(limit = 100).getOrElse { emptyList() },
            localCurrent = latestInstalledChangelog,
        )
            .filter { entry ->
                entry.versionCode > installedVersionCode && entry.versionCode <= eligibleRemote.versionCode
            }
            .sortedWith(releaseChangelogNewestFirstComparator())

        startupUpdateReleaseHistory = if (startupHistory.isNotEmpty()) {
            startupHistory
        } else {
            listOf(eligibleRemote.toReleaseHistoryEntry())
        }
        updateStartupWorkflow(StartupUpdateWorkflowEvent.EligibleUpdateFound(eligibleRemote))
    }

    LaunchedEffect(Unit) {
        if (navReady) {
            updateStartupWorkflow(StartupUpdateWorkflowEvent.ContinuedToApp())
        } else {
            beginStartupUpdateFlow()
        }
    }

    LaunchedEffect(incomingUri) {
        val uri = incomingUri ?: return@LaunchedEffect
        if (sourceDependencies.pixivAuthController.isAuthorizationCallback(uri)) {
            val result = sourceDependencies.pixivAuthController.handleAuthorizationCallback(uri)
            if (result.isSuccess) {
                pixivStatusLabel = "Connected"
                refreshSourceAccountState()
            } else {
                pixivStatusLabel = "Connection failed: ${result.exceptionOrNull()?.message ?: "Unknown error"}"
            }
        } else {
            appShellOwner.onAction(
                AppShellAction.AcceptIncomingUri(
                    uri = uri.toString(),
                    isPixivAuthorizationCallback = false,
                    isCodexImport = isCodexImportUri(appContext, uri),
                )
            )
        }
        onIncomingUriConsumed()
    }

    val pendingCodexImport = appShellState.pendingIncomingUri
        ?.takeIf { pending -> pending.kind == IncomingUriKind.CODEX_IMPORT }
    LaunchedEffect(navReady, pendingCodexImport) {
        if (!navReady) return@LaunchedEffect
        val pending = pendingCodexImport ?: return@LaunchedEffect
        val uri = Uri.parse(pending.value)
        fun consumePendingImportUriIfCurrent() {
            appShellOwner.onAction(AppShellAction.ConsumeIncomingUri(pending))
        }
        runCatching {
            appContext.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        try {
            importCodexFromUri(uri)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            Toast.makeText(appContext, "Could not import codex file", Toast.LENGTH_SHORT).show()
        } finally {
            consumePendingImportUriIfCurrent()
        }
    }

    val pendingExternalContent = appShellState.pendingIncomingUri
        ?.takeIf { pending -> pending.kind == IncomingUriKind.EXTERNAL_CONTENT }
    LaunchedEffect(navReady, pendingExternalContent) {
        if (!navReady) return@LaunchedEffect
        val pending = pendingExternalContent ?: return@LaunchedEffect
        val uri = Uri.parse(pending.value)
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
            val creator = resolveExternalCreatorDeepLink(creatorDeepLink)
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
            ),
        )
        dataDependencies.uiRestoreRepository.setViewerLaunchContext(context)
        navController.navigate(AppRoute.Viewer) {
            launchSingleTop = true
        }
        consumePendingUriIfCurrent()
    }

    LaunchedEffect(lifecycleOwner, codices.map { it.codexId }, availableRealSources) {
        val activeIds = codices.map { it.codexId }.toSet()
        codexItemCounts.keys
            .filterNot { it in activeIds }
            .toList()
            .forEach { codexItemCounts.remove(it) }
        codexCoverModels.keys
            .filterNot { it in activeIds }
            .toList()
            .forEach { codexCoverModels.remove(it) }
        codexSearchSourceOptions.keys
            .filterNot { it in activeIds }
            .toList()
            .forEach { codexSearchSourceOptions.remove(it) }
        codexSearchTagOptions.keys
            .filterNot { it in activeIds }
            .toList()
            .forEach { codexSearchTagOptions.remove(it) }
        savedPostIdsByCodex.keys
            .filterNot { it in activeIds }
            .toList()
            .forEach { savedPostIdsByCodex.remove(it) }

        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            coroutineScope {
                codices.forEach { codex ->
                    launch {
                        dataDependencies.codexRepository.observeCodexItems(codex.codexId).collect { items ->
                            codexItemCounts[codex.codexId] = items.size
                            savedPostIdsByCodex[codex.codexId] = items
                                .asSequence()
                                .map { item -> item.postId }
                                .toSet()
                        }
                    }
                    launch {
                        dataDependencies.codexRepository
                            .observeCodexPosts(codex.codexId, CodexSortMode.NEWEST_SAVED)
                            .collect { posts ->
                                val coverModel = posts.firstOrNull()?.let { post ->
                                    resolveCodexCoverModel(
                                        storageDirectory = dataDependencies.storageDirectory,
                                        post = post,
                                    )
                                }
                                codexCoverModels[codex.codexId] = coverModel
                                val sourceOptions = buildCodexSearchSourceOptions(
                                    posts = posts,
                                    availableSources = availableRealSources,
                                )
                                codexSearchSourceOptions[codex.codexId] = sourceOptions
                                codexSearchTagOptions[codex.codexId] = sourceOptions.associate { option ->
                                    option.source to buildCodexSearchTagOptions(
                                        posts = posts,
                                        source = option.source,
                                    )
                                }
                            }
                    }
                }
            }
        }
    }

    LaunchedEffect(activeRecommendationProfile.profileId, activeRecommendationProfile.name) {
        ensureLikesCodexId(activeRecommendationProfile)
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val currentRoute = currentDestination?.route
    val showBottomBar = currentRoute == AppRoute.Home
    val currentContext = LocalContext.current
    val configuration = LocalConfiguration.current
    val installedVersionCode = remember(appContext) { installedAppVersionCode(appContext) }
    val hostActivity = remember(currentContext) { currentContext.findActivity() }
    val topLevelPagerState = rememberPagerState(
        pageCount = { TopLevelDestination.entries.size },
    )
    val selectedTopLevelIndex = topLevelPagerState.currentPage
        .coerceIn(0, TopLevelDestination.entries.lastIndex)
    var persistedHomeTabRoute by rememberSaveable { mutableStateOf<String?>(null) }
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

    DisposableEffect(hostActivity, currentRoute, configuration.orientation) {
        val window = hostActivity?.window
        val insetsController = window?.let {
            WindowInsetsControllerCompat(it, it.decorView)
        }
        val shouldUseLandscapeFullscreen =
            currentRoute == AppRoute.Viewer &&
                configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

        if (shouldUseLandscapeFullscreen) {
            insetsController?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController?.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            insetsController?.show(WindowInsetsCompat.Type.statusBars())
        }

        onDispose {
            insetsController?.show(WindowInsetsCompat.Type.statusBars())
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
                    val outcome = updateDependencies.startupUpdater.retryPendingInstall(remote) { state ->
                        updateStartupWorkflow(StartupUpdateWorkflowEvent.UpdaterStateChanged(state))
                    }
                    when (outcome) {
                        StartupUpdateOutcome.ContinueToApp -> {
                            updateStartupWorkflow(StartupUpdateWorkflowEvent.InstallFinished(outcome))
                        }

                        is StartupUpdateOutcome.ContinueToAppWithError -> {
                            continueAfterUpdateFailure(outcome.message)
                        }

                        is StartupUpdateOutcome.AwaitingUnknownSources -> {
                            continueAfterUpdateFailure(
                                "Install permission was not granted. Continuing with current app.",
                            )
                        }

                        is StartupUpdateOutcome.InstallerLaunched -> {
                            updateStartupWorkflow(StartupUpdateWorkflowEvent.InstallFinished(outcome))
                        }
                    }
                }
            } else if (awaitingInstallerReturn && remote != null) {
                scope.launch {
                    val installedNow = installedAppVersionCode(appContext)
                    if (installedNow >= remote.versionCode) {
                        updateStartupWorkflow(
                            StartupUpdateWorkflowEvent.ContinuedToApp("Update installed. Loading app...")
                        )
                    } else {
                        updateStartupWorkflow(StartupUpdateWorkflowEvent.InstallerStillPending)
                    }
                }
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
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
                                updateStartupWorkflow(StartupUpdateWorkflowEvent.InstallStarted)
                                updateDependencies.startupUpdater.onUserChoseNo(promptRemote)
                                startupUpdateReleaseHistory = emptyList()
                                updateStartupWorkflow(
                                    StartupUpdateWorkflowEvent.ContinuedToApp("Update skipped. Loading app...")
                                )
                            }
                        },
                        onRemindLater = {
                            if (startupActionLocked) return@StartupUpdatePromptCard
                            scope.launch {
                                updateStartupWorkflow(StartupUpdateWorkflowEvent.InstallStarted)
                                updateDependencies.startupUpdater.onUserChoseRemindLater(promptRemote)
                                startupUpdateReleaseHistory = emptyList()
                                updateStartupWorkflow(
                                    StartupUpdateWorkflowEvent.ContinuedToApp(
                                        "Update postponed for 24 hours. Loading app..."
                                    )
                                )
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
                                val selected = selectedTopLevelIndex == TopLevelDestination.entries.indexOf(destination)
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = {
                                        homeTabRoute = destination.route
                                        val targetIndex = TopLevelDestination.entries.indexOf(destination)
                                        scope.launch {
                                            if (currentRoute != AppRoute.Home) {
                                                navController.navigate(AppRoute.Home) {
                                                    popUpTo(navController.graph.findStartDestination().id) {
                                                        saveState = true
                                                    }
                                                    launchSingleTop = true
                                                    restoreState = true
                                                }
                                                topLevelPagerState.scrollToPage(targetIndex)
                                            } else if (topLevelPagerState.currentPage != targetIndex) {
                                                topLevelPagerState.animateScrollToPage(targetIndex)
                                            }
                                        }
                                    },
                                    icon = {
                                        val icon = when (destination) {
                                            TopLevelDestination.Search -> Icons.Default.Search
                                            TopLevelDestination.Recents -> Icons.Default.History
                                            TopLevelDestination.ForYou -> Icons.Default.Favorite
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
                    startDestination = AppRoute.Home,
                    modifier = Modifier.padding(innerPadding),
                ) {
                    composable(AppRoute.Home) {
                        HorizontalPager(
                            state = topLevelPagerState,
                            modifier = Modifier.fillMaxSize(),
                        ) { page ->
                            when (TopLevelDestination.entries[page]) {
                                TopLevelDestination.Search -> {
                                    SearchRoute(
                                        coordinator = featureDependencies.search,
                                        pixivUgoiraClient = sourceDependencies.pixivUgoiraClient,
                                        config = SearchRouteConfig(
                                            settings = settings,
                                            availableSources = availableRealSources,
                                            likedPostIds = likedPostIds,
                                            savedPostIds = savedPostIds,
                                            favoriteTags = activeProfileFavoriteTags,
                                            resolveUnknownAnimatedDurations =
                                                settings.contentFilters.resolveUnknownAnimatedDurations,
                                        ),
                                        callbacks = SearchRouteCallbacks(
                                            onOpenViewer = { effect ->
                                                val preparedPosts = viewerRouteWorkflow.preparePostsForLaunch(
                                                    effect.posts,
                                                    effect.context,
                                                )
                                                viewerSessionOwner.retain(
                                                    ViewerSession(
                                                        posts = preparedPosts,
                                                        context = effect.context,
                                                        liveSearchBinding = effect.liveSearchBinding,
                                                        searchVisibilityFilters = effect.visibilityFilters,
                                                    ),
                                                )
                                                navController.navigate(AppRoute.Viewer) {
                                                    launchSingleTop = !effect.liveSearchBinding
                                                }
                                            },
                                            onShowMessage = { message ->
                                                Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
                                            },
                                            onToggleLike = { post ->
                                                scope.launch { toggleLikeAndSyncCodex(post) }
                                            },
                                            onOpenCreatorProfile = { creator ->
                                                scope.launch { openCreatorProfile(creator) }
                                            },
                                            onOpenLegacyCreatorProfile = { post ->
                                                scope.launch { openCreatorProfile(post) }
                                            },
                                            onRequestSaveToCodex = { post ->
                                                pendingSavePost = post
                                                showSaveSheet = true
                                            },
                                            onSaveToDevice = ::requestSaveToDevice,
                                            onAddFavoriteTag = addFavoriteTag,
                                            onRemoveFavoriteTag = removeFavoriteTag,
                                        ),
                                        onOwnerAvailable = { owner ->
                                            searchRouteOwner = owner
                                            pendingSearchActions.flush(owner::dispatch)
                                        },
                                    )
                                }

                                TopLevelDestination.ForYou -> {
                                    ForYouRoute(
                                        coordinator = featureDependencies.forYou,
                                        pixivUgoiraClient = sourceDependencies.pixivUgoiraClient,
                                        config = ForYouRouteConfig(
                                            settings = settings,
                                            activeProfileLikesCount = activeProfileLikes.size,
                                            availableSources = availableRealSources,
                                            likedPostIds = likedPostIds,
                                            resolveUnknownAnimatedDurations =
                                                settings.contentFilters.resolveUnknownAnimatedDurations,
                                        ),
                                        callbacks = ForYouRouteCallbacks(
                                            onOpenViewer = { effect ->
                                                val preparedPosts = viewerRouteWorkflow.preparePostsForLaunch(
                                                    effect.posts,
                                                    effect.context,
                                                )
                                                viewerSessionOwner.retain(
                                                    ViewerSession(
                                                        posts = preparedPosts,
                                                        context = effect.context,
                                                        liveSearchBinding = true,
                                                        searchVisibilityFilters = effect.visibilityFilters,
                                                    ),
                                                )
                                                featureDependencies.search.setViewerLaunchContext(effect.context)
                                                navController.navigate(AppRoute.Viewer)
                                            },
                                            onNavigateToSearch = {
                                                val targetIndex = TopLevelDestination.entries
                                                    .indexOf(TopLevelDestination.Search)
                                                homeTabRoute = TopLevelDestination.Search.route
                                                if (topLevelPagerState.currentPage != targetIndex) {
                                                    topLevelPagerState.animateScrollToPage(targetIndex)
                                                }
                                            },
                                            onShowMessage = { message ->
                                                Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
                                            },
                                            onToggleLike = { post ->
                                                scope.launch { toggleLikeAndSyncCodex(post) }
                                            },
                                        ),
                                        onOwnerAvailable = { owner -> forYouRouteOwner = owner },
                                    )
                                }

                                TopLevelDestination.Recents -> {
                                    RecentsScreen(
                                        watchedPosts = recentWatchedPosts,
                                        searches = recentSearches,
                                        activity = recentActivity,
                                        pixivUgoiraClient = sourceDependencies.pixivUgoiraClient,
                                        likedPostIds = likedPostIds,
                                        onToggleLike = { post ->
                                            scope.launch {
                                                toggleLikeAndSyncCodex(post)
                                            }
                                        },
                                        onOpenWatchedPost = { index ->
                                            val posts = recentWatchedPosts.map { entry -> entry.post }
                                            if (posts.isNotEmpty()) {
                                                val context = ViewerLaunchContext(
                                                    queryHash = "recents:watched",
                                                    startIndex = index.coerceIn(0, posts.lastIndex),
                                                    streamSource = ViewerStreamSource.RECENTS,
                                                    scrollOffsetHint = 0,
                                                )
                                                scope.launch {
                                                    val preparedPosts = viewerRouteWorkflow.preparePostsForLaunch(
                                                        posts,
                                                        context,
                                                    )
                                                    viewerSessionOwner.retain(
                                                        ViewerSession(
                                                            posts = preparedPosts,
                                                            context = context,
                                                            liveSearchBinding = false,
                                                        ),
                                                    )
                                                    dataDependencies.uiRestoreRepository.setViewerLaunchContext(context)
                                                    navController.navigate(AppRoute.Viewer)
                                                }
                                            }
                                        },
                                        onOpenSearch = { entry ->
                                            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                                dispatchOrQueueSearchAction(
                                                    SearchAction.ApplyHistoricalQuery(entry.query),
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
                                                    topLevelPagerState.animateScrollToPage(targetIndex)
                                                }
                                            }
                                        },
                                        onClearWatched = {
                                            scope.launch { dataDependencies.recentsRepository.clearWatchedPosts() }
                                        },
                                        onClearSearches = {
                                            scope.launch { dataDependencies.recentsRepository.clearSearches() }
                                        },
                                        onClearAll = {
                                            scope.launch { dataDependencies.recentsRepository.clearAll() }
                                        },
                                    )
                                }

                                TopLevelDestination.Codex -> {
                                    CodexListScreen(
                                        codices = visibleCodices,
                                        itemCounts = codexItemCounts,
                                        codexCoverModels = codexCoverModels,
                                        codexSearchSourceOptions = codexSearchSourceOptions,
                                        codexSearchTagOptions = codexSearchTagOptions,
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
                                        onCommitReorder = { orderedVisibleIds ->
                                            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                                commitVisibleCodexOrder(orderedVisibleIds)
                                            }
                                        },
                                        onCreateCodex = { name ->
                                            scope.launch {
                                                dataDependencies.codexRepository.ensureCodex(
                                                    codexId = profileScopedCodexId(activeRecommendationProfile.profileId),
                                                    name = name,
                                                )
                                            }
                                        },
                                        onRenameCodex = { codexId, name ->
                                            scope.launch { dataDependencies.codexRepository.renameCodex(codexId, name) }
                                        },
                                        onDeleteCodex = { codexId ->
                                            scope.launch { dataDependencies.codexRepository.deleteCodex(codexId) }
                                        },
                                    )
                                }

                                TopLevelDestination.Settings -> {
                                    SettingsScreen(
                                        settings = settings,
                                        recommendationProfiles = settings.recommendationProfiles,
                                        activeProfileId = activeRecommendationProfile.profileId,
                                        activeProfileName = activeRecommendationProfile.name,
                                        likesCount = activeProfileLikes.size,
                                        forYouBlacklistEntries = activeProfileForYouBlacklist,
                                        availableSources = featureDependencies.search.availableSources,
                                        cacheSnapshot = cacheSnapshot,
                                        showDeveloperScenarios = false,
                                        pixivStatusLabel = pixivStatusLabel,
                                        pixivConnectEnabled = !pixivConnected &&
                                            credentialRecoveryState == CredentialStoreRecoveryState.Ready &&
                                            !pixivStatusLabel.startsWith("Awaiting authorization callback"),
                                        onPixivConnect = {
                                            if (credentialRecoveryState == CredentialStoreRecoveryState.ReconnectRequired) {
                                                showCredentialRecoveryDialog = true
                                            } else {
                                                val authUrl = sourceDependencies.pixivAuthController.startAuthorizationUri().toString()
                                                pixivStatusLabel = "Awaiting authorization callback..."
                                                pixivConnected = false
                                                openInBrowser(appContext, authUrl)
                                            }
                                        },
                                        onPixivDisconnect = {
                                            if (credentialRecoveryState == CredentialStoreRecoveryState.ReconnectRequired) {
                                                showCredentialRecoveryDialog = true
                                            } else {
                                                scope.launch {
                                                    sourceDependencies.accounts.clearPixivTokens()
                                                    refreshSourceAccountState()
                                                }
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
                                            if (credentialRecoveryState == CredentialStoreRecoveryState.ReconnectRequired) {
                                                showCredentialRecoveryDialog = true
                                            } else scope.launch {
                                                if (gelbooruUserIdInput.isBlank() || gelbooruApiKeyInput.isBlank()) {
                                                    gelbooruStatusLabel = "Missing user ID or API key"
                                                } else {
                                                    sourceDependencies.accounts.saveGelbooruCredentials(
                                                        GelbooruCredentials(
                                                            userId = gelbooruUserIdInput,
                                                            apiKey = gelbooruApiKeyInput,
                                                        )
                                                    )
                                                    refreshSourceAccountState()
                                                }
                                            }
                                        },
                                        onClearGelbooruCredentials = {
                                            if (credentialRecoveryState == CredentialStoreRecoveryState.ReconnectRequired) {
                                                showCredentialRecoveryDialog = true
                                            } else {
                                                scope.launch {
                                                    sourceDependencies.accounts.clearGelbooruCredentials()
                                                    refreshSourceAccountState()
                                                }
                                            }
                                        },
                                        rule34XxxUserId = rule34XxxUserIdInput,
                                        rule34XxxApiKey = rule34XxxApiKeyInput,
                                        rule34XxxStatusLabel = rule34XxxStatusLabel,
                                        onRule34XxxUserIdChange = { rule34XxxUserIdInput = it.trim() },
                                        onRule34XxxApiKeyChange = { input ->
                                            val parsed = parseRule34XxxCredentialInput(input)
                                            if (parsed != null) {
                                                rule34XxxApiKeyInput = parsed.apiKey
                                                rule34XxxUserIdInput = parsed.userId
                                            } else {
                                                rule34XxxApiKeyInput = input.trim()
                                            }
                                        },
                                        onSaveRule34XxxCredentials = {
                                            if (credentialRecoveryState == CredentialStoreRecoveryState.ReconnectRequired) {
                                                showCredentialRecoveryDialog = true
                                            } else scope.launch {
                                                if (rule34XxxUserIdInput.isBlank() || rule34XxxApiKeyInput.isBlank()) {
                                                    rule34XxxStatusLabel = "Missing user ID or API key"
                                                } else {
                                                    sourceDependencies.accounts.saveRule34XxxCredentials(
                                                        Rule34XxxCredentials(
                                                            userId = rule34XxxUserIdInput,
                                                            apiKey = rule34XxxApiKeyInput,
                                                        )
                                                    )
                                                    refreshSourceAccountState()
                                                }
                                            }
                                        },
                                        onClearRule34XxxCredentials = {
                                            if (credentialRecoveryState == CredentialStoreRecoveryState.ReconnectRequired) {
                                                showCredentialRecoveryDialog = true
                                            } else {
                                                scope.launch {
                                                    sourceDependencies.accounts.clearRule34XxxCredentials()
                                                    refreshSourceAccountState()
                                                }
                                            }
                                        },
                                        onSetEnabledSources = { enabled ->
                                            scope.launch {
                                                dataDependencies.settingsRepository.setEnabledSources(
                                                    enabled.intersect(featureDependencies.search.availableSources.toSet())
                                                )
                                            }
                                        },
                                        onSetSourceWeights = { weights ->
                                            scope.launch { dataDependencies.settingsRepository.setSourceWeights(weights) }
                                        },
                                        onSetActiveProfile = { profileId ->
                                            scope.launch { dataDependencies.settingsRepository.setActiveProfile(profileId) }
                                        },
                                        onAddProfile = { name ->
                                            scope.launch { dataDependencies.settingsRepository.addRecommendationProfile(name) }
                                        },
                                        onRemoveProfile = { profileId ->
                                            scope.launch {
                                                val canRemove = settings.recommendationProfiles.size > 1 &&
                                                    settings.recommendationProfiles.any { profile -> profile.profileId == profileId }
                                                if (!canRemove) return@launch
                                                clearProfileLikesAndSyncCodex(profileId)
                                                removeProfileLikesCodex(profileId)
                                                removeProfileScopedCodices(profileId)
                                                dataDependencies.settingsRepository.removeRecommendationProfile(profileId)
                                            }
                                        },
                                        onClearLikesForActiveProfile = {
                                            scope.launch { clearProfileLikesAndSyncCodex(activeRecommendationProfile.profileId) }
                                        },
                                        onRemoveForYouBlacklistEntry = { source, tags ->
                                            scope.launch {
                                                dataDependencies.settingsRepository.removeForYouBlacklistEntry(
                                                    profileId = activeRecommendationProfile.profileId,
                                                    source = source,
                                                    tags = tags,
                                                )
                                            }
                                        },
                                        onSetCacheFullImageOnSave = { enabled ->
                                            scope.launch { dataDependencies.settingsRepository.setCacheFullImageOnSave(enabled) }
                                        },
                                        onSetResolveUnknownAnimatedDurations = { enabled ->
                                            scope.launch { dataDependencies.settingsRepository.setResolveUnknownAnimatedDurations(enabled) }
                                        },
                                        onSetScenarioPreset = { preset ->
                                            scope.launch { dataDependencies.settingsRepository.setScenarioPreset(preset) }
                                        },
                                        onClearThumbnailCache = {
                                            scope.launch { dataDependencies.cacheRepository.clearThumbnailCache() }
                                        },
                                        onClearFullImageCache = {
                                            scope.launch { dataDependencies.cacheRepository.clearFullImageCache() }
                                        },
                                        changelogLoading = releaseHistoryLoading,
                                        onOpenChangelog = {
                                            if (releaseHistoryLoading) return@SettingsScreen
                                            scope.launch {
                                                releaseHistoryLoading = true
                                                val remoteHistory = updateDependencies.feedClient.mainPrereleaseHistory(limit = 50)
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
                            }
                        }
                    }
                    composable(
                        route = AppRoute.CodexDetail,
                        arguments = listOf(navArgument("codexId") { type = NavType.StringType }),
                    ) { entry ->
                        val codexId = requireNotNull(entry.arguments?.getString("codexId"))
                        var sortMode by rememberSaveable(codexId) { mutableStateOf(CodexSortMode.NEWEST_SAVED) }
                        val codex by dataDependencies.codexRepository
                            .observeCodex(codexId)
                            .collectAsStateWithLifecycle(initialValue = null)
                        val posts by dataDependencies.codexRepository
                            .observeCodexPosts(codexId, sortMode)
                            .collectAsStateWithLifecycle(initialValue = emptyList())

                        CodexDetailScreen(
                            codexName = codex?.name,
                            posts = posts,
                            sortMode = sortMode,
                            pixivUgoiraClient = sourceDependencies.pixivUgoiraClient,
                            tagVideoCountProvider = { source, tag ->
                                featureDependencies.search.tagVideoCount(source, tag)
                            },
                            fetchTagVideoCounts = { source, tags ->
                                featureDependencies.search.fetchTagVideoCounts(source, tags)
                            },
                            onSortChange = { sortMode = it },
                            resolvePostById = resolver@{ postId ->
                                val adapter = sourceDependencies.registry.adapterFor(postId.source) ?: return@resolver null
                                val resolved = runCatchingPreservingCancellation {
                                    adapter.resolvePost(postId)
                                }.getOrNull() ?: return@resolver null
                                dataDependencies.codexRepository.updatePost(resolved)
                                resolved
                            },
                            onOpenViewer = { index ->
                                val context = ViewerLaunchContext(
                                    queryHash = "codex:$codexId",
                                    startIndex = index,
                                    streamSource = ViewerStreamSource.CODEX,
                                    scrollOffsetHint = 0,
                                )
                                scope.launch {
                                    val preparedPosts = viewerRouteWorkflow.preparePostsForLaunch(posts, context)
                                    viewerSessionOwner.retain(
                                        ViewerSession(
                                            posts = preparedPosts,
                                            context = context,
                                            liveSearchBinding = false,
                                        ),
                                    )
                                    dataDependencies.uiRestoreRepository.setViewerLaunchContext(context)
                                    navController.navigate(AppRoute.Viewer)
                                }
                            },
                            onRemovePost = { post ->
                                scope.launch {
                                    dataDependencies.codexRepository.removeItem(
                                        codexId = codexId,
                                        sourceKey = post.id.source,
                                        sourcePostId = post.id.sourcePostId,
                                    )
                                }
                            },
                            onSavePostToDevice = { post ->
                                requestSaveToDevice(post)
                            },
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
                                homeTabRoute = TopLevelDestination.Search.route
                                navController.popBackStack(AppRoute.Home, inclusive = false)
                            },
                            onBack = {
                                navController.popBackStack()
                            },
                            onDeleteCodex = {
                                scope.launch {
                                    dataDependencies.codexRepository.deleteCodex(codexId)
                                    homeTabRoute = TopLevelDestination.Codex.route
                                    navController.popBackStack(AppRoute.Home, inclusive = false)
                                }
                            },
                        )
                    }
                    composable(AppRoute.CreatorProfile) {
                        CreatorRoute(
                            coordinator = featureDependencies.creatorProfile,
                            pixivUgoiraClient = sourceDependencies.pixivUgoiraClient,
                            config = CreatorRouteConfig(
                                activeCreator = pendingCreatorProfile,
                                availableSources = availableRealSources,
                                likedPostIds = likedPostIds,
                                savedPostIds = savedPostIds,
                                resolveUnknownAnimatedDurations =
                                    settings.contentFilters.resolveUnknownAnimatedDurations,
                            ),
                            callbacks = CreatorRouteCallbacks(
                                onOpenViewer = { effect ->
                                    val preparedPosts = viewerRouteWorkflow.preparePostsForLaunch(
                                        effect.posts,
                                        effect.context,
                                    )
                                    viewerSessionOwner.retain(
                                        ViewerSession(
                                            posts = preparedPosts,
                                            context = effect.context,
                                            liveSearchBinding = true,
                                            searchVisibilityFilters = effect.visibilityFilters,
                                        ),
                                    )
                                    navController.navigate(AppRoute.Viewer)
                                },
                                onNavigateBack = { navController.popBackStack() },
                                onToggleLike = { post ->
                                    scope.launch { toggleLikeAndSyncCodex(post) }
                                },
                                onRequestSaveToCodex = { post ->
                                    pendingSavePost = post
                                    showSaveSheet = true
                                },
                                onSaveToDevice = ::requestSaveToDevice,
                                onOpenUrl = { url -> openInBrowser(appContext, url) },
                                onAddIncludeTerm = ::addSearchIncludeTerm,
                                onAddExcludeTerm = ::addSearchExcludeTerm,
                                onRemoveIncludeTerm = { _, term -> removeSearchIncludeTerm(term) },
                                onRemoveExcludeTerm = { _, term -> removeSearchExcludeTerm(term) },
                            ),
                            onOwnerAvailable = { owner -> creatorRouteOwner = owner },
                        )
                    }
                    composable(AppRoute.Viewer) {
                        ViewerRoute(
                            dependencies = ViewerRouteDependencies(
                                sessionRetentionOwner = viewerSessionOwner,
                                postResolver = ViewerPostResolver { identity, postId ->
                                    val streamSource = identity.streamKey
                                        ?.let { name ->
                                            ViewerStreamSource.entries.firstOrNull { source -> source.name == name }
                                        }
                                        ?: ViewerStreamSource.SEARCH
                                    viewerRouteWorkflow.resolvePost(postId, streamSource)
                                },
                                mediaPrefetcher = ViewerMediaPrefetcher { _, media ->
                                    prefetchViewerMedia(appContext, media)
                                },
                                restoreSession = viewerRouteWorkflow::restoreSession,
                            ),
                            renderConfig = ViewerRouteRenderConfig(
                                pixivUgoiraClient = sourceDependencies.pixivUgoiraClient,
                                tagVideoCountProvider = featureDependencies.search::tagVideoCount,
                                fetchTagVideoCounts = featureDependencies.search::fetchTagVideoCounts,
                                invertMultiImageScrollDirection = settings.viewer.invertMultiImageScrollDirection,
                                likedPostIds = likedPostIds,
                            ),
                            liveSourceState = ViewerRouteLiveSourceState(
                                search = ViewerRouteLiveSourceSnapshot(
                                    queryHash = searchRouteState.query.appliedQueryHash
                                        .takeIf(String::isNotBlank),
                                    results = searchRouteState.content.results,
                                    canLoadMore = searchRouteState.content.canLoadMore,
                                    loadingMore = searchRouteState.loadingMore,
                                ),
                                forYou = ViewerRouteLiveSourceSnapshot(
                                    queryHash = "for_you:${forYouRouteState.seedId}",
                                    results = forYouRouteState.results,
                                    canLoadMore = forYouRouteState.canLoadMore,
                                    loadingMore = forYouRouteState.isPaging,
                                ),
                                creatorProfile = ViewerRouteLiveSourceSnapshot(
                                    queryHash = creatorRouteState.queryHash,
                                    results = creatorRouteState.results,
                                    canLoadMore = creatorRouteState.canLoadMore,
                                    loadingMore = creatorRouteState.isPaging,
                                ),
                                likedPostIds = likedPostIds,
                                savedPostIds = savedPostIds,
                                unknownAnimatedDurationPolicy = unknownAnimatedDurationPolicy,
                            ),
                            effectCallbacks = ViewerRouteEffectCallbacks(
                                onSavePost = { post ->
                                    pendingSavePost = post
                                    showSaveSheet = true
                                },
                                onSharePost = { post ->
                                    Toast.makeText(
                                        appContext,
                                        shareViewerPostMessage(appContext, post),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                                onDownloadMedia = { request ->
                                    val message = downloadViewerMediaMessage(
                                        context = appContext,
                                        sources = sourceDependencies,
                                        request = request,
                                    )
                                    Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
                                },
                                onToggleLike = ::toggleLikeAndSyncCodex,
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
                                onOwnerChanged = { owner -> activeViewerOwner = owner },
                                onInvertMultiImageScrollDirectionChange = { enabled ->
                                    scope.launch {
                                        dataDependencies.settingsRepository
                                            .setInvertMultiImageScrollDirection(enabled)
                                    }
                                },
                                onVisiblePostChanged = { post ->
                                    scope.launch {
                                        viewerRouteWorkflow.recordVisiblePost(post, viewerSession)
                                    }
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
                                    homeTabRoute = TopLevelDestination.Search.route
                                    scope.launch { featureDependencies.search.setViewerLaunchContext(null) }
                                    navController.popBackStack(AppRoute.Viewer, inclusive = true)
                                    navController.navigate(AppRoute.Home) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                    scope.launch {
                                        val searchIndex = TopLevelDestination.entries.indexOf(TopLevelDestination.Search)
                                        topLevelPagerState.scrollToPage(searchIndex)
                                    }
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

        if (showSaveSheet && pendingSavePost != null) {
            val post = requireNotNull(pendingSavePost)
            SaveToCodexSheet(
                profiles = settings.recommendationProfiles,
                initialProfileId = activeRecommendationProfile.profileId,
                codicesByProfile = saveSheetCodicesByProfile,
                codexItemCounts = codexItemCounts,
                codexCoverModels = codexCoverModels,
                onCreateCodex = { profileId, name ->
                    scope.launch {
                        val codex = dataDependencies.codexRepository.ensureCodex(
                            codexId = profileScopedCodexId(profileId),
                            name = name,
                        )
                        dataDependencies.codexRepository.addItem(codex.codexId, post)
                        dataDependencies.cacheRepository.cacheThumbnail(post)
                        if (settings.cache.cacheFullImageOnSave) {
                            dataDependencies.cacheRepository.cacheFull(post)
                        }
                    }
                    showSaveSheet = false
                    pendingSavePost = null
                },
                onSelectCodex = { codexId ->
                    scope.launch {
                        dataDependencies.codexRepository.addItem(codexId, post)
                        dataDependencies.cacheRepository.cacheThumbnail(post)
                        if (settings.cache.cacheFullImageOnSave) {
                            dataDependencies.cacheRepository.cacheFull(post)
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
                        updateDependencies.stateStore.setPendingPostInstallChangelog(null)
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

        if (
            showCredentialRecoveryDialog &&
            credentialRecoveryState == CredentialStoreRecoveryState.ReconnectRequired
        ) {
            AlertDialog(
                onDismissRequest = { showCredentialRecoveryDialog = false },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showCredentialRecoveryDialog = false
                            scope.launch {
                                val reset = sourceDependencies.accounts.resetAfterReconnectRequired()
                                refreshSourceAccountState()
                                if (reset) {
                                    pendingTopLevelRoute = TopLevelDestination.Settings.route
                                    homeTabRoute = TopLevelDestination.Settings.route
                                    if (navReady && currentRoute != AppRoute.Home) {
                                        val returnedHome = navController.popBackStack(
                                            route = AppRoute.Home,
                                            inclusive = false,
                                        )
                                        if (!returnedHome) {
                                            navController.navigate(AppRoute.Home) {
                                                launchSingleTop = true
                                            }
                                        }
                                    }
                                    Toast.makeText(
                                        appContext,
                                        "Reconnect each source account in Settings",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                } else {
                                    showCredentialRecoveryDialog = true
                                    Toast.makeText(
                                        appContext,
                                        "Could not reset source credentials",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        },
                    ) {
                        Text("Reconnect")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showCredentialRecoveryDialog = false }) {
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
            ReleaseChangelogList(
                releases = releases,
                installedVersionCode = installedVersionCode,
                maxHeight = 240.dp,
                itemSpacing = 10.dp,
            )

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
            ReleaseChangelogList(
                releases = releases,
                installedVersionCode = installedVersionCode,
                maxHeight = 320.dp,
                itemSpacing = 14.dp,
            )
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
            ReleaseChangelogList(
                releases = releases,
                installedVersionCode = installedVersionCode,
                maxHeight = 420.dp,
                itemSpacing = 14.dp,
            )
        },
    )
}

@Composable
private fun ReleaseChangelogList(
    releases: List<ReleaseChangelogEntry>,
    installedVersionCode: Int,
    maxHeight: Dp,
    itemSpacing: Dp,
) {
    if (releases.isEmpty()) {
        Text(
            text = "No changelog details were published for this build.",
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(itemSpacing),
    ) {
        releases.forEachIndexed { index, release ->
            ReleaseChangelogEntryContent(
                release = release,
                installedVersionCode = installedVersionCode,
            )
            if (index != releases.lastIndex) {
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ReleaseChangelogEntryContent(
    release: ReleaseChangelogEntry,
    installedVersionCode: Int,
) {
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
}

private fun openInBrowser(context: Context, url: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

private fun isCodexImportUri(context: Context, uri: Uri): Boolean {
    val scheme = uri.scheme?.lowercase().orEmpty()
    if (scheme != "content" && scheme != "file") return false

    val path = uri.path?.lowercase().orEmpty()
    val lastSegment = uri.lastPathSegment?.lowercase().orEmpty()
    if (path.endsWith(".json") || lastSegment.endsWith(".json")) {
        return true
    }

    val mimeType = runCatching {
        context.contentResolver.getType(uri)
    }.getOrNull()?.lowercase()
    if (mimeType == null || mimeType in CODEX_IMPORT_MIME_TYPES || mimeType == "application/octet-stream") {
        return true
    }
    return false
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

private fun resolveCodexCoverModel(
    storageDirectory: File,
    post: Post,
): Any? {
    val key = "${post.id.source.name}_${post.id.sourcePostId}"
    val thumbnailDirectory = storageDirectory.resolve("cache/thumbnails")
    val localCover = thumbnailDirectory
        .listFiles()
        ?.firstOrNull { file ->
            file.isFile && file.name.startsWith("$key.") && !file.name.endsWith(".url")
        }
    if (localCover != null) {
        return localCover
    }

    val remotePointer = thumbnailDirectory.resolve("$key.url")
    if (remotePointer.exists()) {
        val remoteUrl = runCatching { remotePointer.readText().trim() }.getOrNull()
        if (!remoteUrl.isNullOrBlank()) {
            return normalizeMediaUrl(post.id.source, remoteUrl)
        }
    }

    val previewLocalPath = post.preview.localPath
    if (!previewLocalPath.isNullOrBlank()) {
        val previewFile = File(previewLocalPath)
        if (previewFile.exists()) {
            return previewFile
        }
    }

    return normalizeMediaUrl(post.id.source, post.preview.url)
}

private fun parseGelbooruProfileOwner(html: String): String? {
    val owner = GELBOORU_PROFILE_OWNER_REGEX.find(html)?.groupValues?.getOrNull(1)
    return owner?.trim()?.takeIf(String::isNotBlank)
}

private const val PIXIV_TOKEN_REFRESH_TIMEOUT_MS = 6_000L
private const val CREDENTIAL_RECONNECT_MESSAGE = "Source credentials need to be reconnected"
private const val BOTTOM_BAR_HEIGHT_RATIO = 0.085f
private const val BOTTOM_BAR_ICON_RATIO = 0.38f
private const val MIN_BOTTOM_BAR_HEIGHT_DP = 68
private const val MAX_BOTTOM_BAR_HEIGHT_DP = 88
private const val MIN_BOTTOM_BAR_ICON_DP = 24
private const val MAX_BOTTOM_BAR_ICON_DP = 30
private val CODEX_IMPORT_MIME_TYPES = setOf("application/json", "text/json")
private val GELBOORU_PROFILE_OWNER_REGEX = Regex("""user:([A-Za-z0-9_:-]+)""", RegexOption.IGNORE_CASE)
