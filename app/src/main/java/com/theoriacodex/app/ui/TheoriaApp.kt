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
import com.google.gson.Gson
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
import androidx.compose.runtime.collectAsState
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
import com.theoriacodex.app.media.recoverRemoteMedia
import com.theoriacodex.app.codex.CodexDetailScreen
import com.theoriacodex.app.codex.CodexListScreen
import com.theoriacodex.app.codex.CodexSearchSourceOption
import com.theoriacodex.app.codex.CodexSearchTagOption
import com.theoriacodex.app.codex.SaveToCodexSheet
import com.theoriacodex.app.codex.buildCodexShareFile
import com.theoriacodex.app.codex.codexSearchSourceOptions as buildCodexSearchSourceOptions
import com.theoriacodex.app.codex.codexSearchTagOptions as buildCodexSearchTagOptions
import com.theoriacodex.app.codex.parseCodexShareFile
import com.theoriacodex.app.codex.resolveCodexShareImportPost
import com.theoriacodex.app.codex.sanitizeCodexExportName
import com.theoriacodex.app.codex.selectCodexShareEntries
import com.theoriacodex.app.creator.CreatorProfileScreen
import com.theoriacodex.app.creator.browseableCreatorProfile
import com.theoriacodex.app.recommend.ForYouScreen
import com.theoriacodex.app.recommend.trainingTagsFor
import com.theoriacodex.app.recents.RecentsScreen
import com.theoriacodex.app.search.SearchScreen
import com.theoriacodex.app.search.SearchVisibilityFilters
import com.theoriacodex.app.search.UnknownAnimatedDurationPolicy
import com.theoriacodex.app.search.filterSearchResults
import com.theoriacodex.app.source.ExternalCreatorDeepLink
import com.theoriacodex.app.source.ExternalPostDeepLink
import com.theoriacodex.app.settings.SettingsScreen
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.source.exposedRealSources
import com.theoriacodex.app.source.parseExternalCreatorDeepLink
import com.theoriacodex.app.source.parseExternalPostDeepLink
import com.theoriacodex.app.source.requestHeaders
import com.theoriacodex.app.sourceauth.parseGelbooruCredentialInput
import com.theoriacodex.app.sourceauth.parseRule34XxxCredentialInput
import com.theoriacodex.app.ui.theme.TheoriaNightTheme
import com.theoriacodex.app.update.ChangelogSection
import com.theoriacodex.app.update.RemoteUpdate
import com.theoriacodex.app.update.PendingPostInstallChangelog
import com.theoriacodex.app.update.StartupUpdateOutcome
import com.theoriacodex.app.update.StartupUpdateState
import com.theoriacodex.app.update.UnknownSourcesPermissionRequiredException
import com.theoriacodex.app.update.messageText
import com.theoriacodex.app.viewer.ViewerScreen
import com.theoriacodex.app.viewer.ViewerSession
import com.theoriacodex.app.viewer.mergeViewerPosts
import com.theoriacodex.app.viewer.requiresLazyMediaResolution
import com.theoriacodex.app.viewer.requiresViewerPostResolution
import com.theoriacodex.app.viewer.requiresPrelaunchViewerPostResolution
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.CacheSnapshot
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.data.repository.ForYouBlacklistEntry
import com.theoriacodex.data.repository.RecommendationProfile
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.credentials.GelbooruCredentials
import com.theoriacodex.sources.credentials.Rule34XxxCredentials
import java.io.File
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

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
    val navController = rememberNavController()
    val appContext = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var rule34XxxConfigured by remember { mutableStateOf(false) }

    val appGraph = rememberTheoriaAppGraph(
        appContext = appContext,
        rule34XxxConfigured = rule34XxxConfigured,
    )
    val storageDirectory = appGraph.storageDirectory
    val sourceHttpClient = appGraph.sourceHttpClient
    val credentialsStore = appGraph.credentialsStore
    val pixivAuthApi = appGraph.pixivAuthApi
    val pixivAuthController = appGraph.pixivAuthController
    val pixivUgoiraClient = appGraph.pixivUgoiraClient
    val availableRealSources = appGraph.availableRealSources
    val realRegistry = appGraph.realRegistry
    val updateStateStore = appGraph.updateStateStore
    val updateFeedClient = appGraph.updateFeedClient
    val startupUpdater = appGraph.startupUpdater
    val codexRepository = appGraph.codexRepository
    val likesRepository = appGraph.likesRepository
    val recentsRepository = appGraph.recentsRepository
    val settingsRepository = appGraph.settingsRepository
    val cacheRepository = appGraph.cacheRepository
    val uiRestoreRepository = appGraph.uiRestoreRepository
    val searchCoordinator = appGraph.searchCoordinator
    val forYouCoordinator = appGraph.forYouCoordinator
    val creatorProfileCoordinator = appGraph.creatorProfileCoordinator

    val settings by settingsRepository.observeSettings().collectAsState(initial = AppSettings())
    val recentWatchedPosts by recentsRepository.observeWatchedPosts().collectAsState(initial = emptyList())
    val recentSearches by recentsRepository.observeSearches().collectAsState(initial = emptyList())
    val recentActivity by recentsRepository.observeActivity().collectAsState(initial = emptyList())
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
    val likedPostIds by likesRepository
        .observeLikedPostIds(activeRecommendationProfile.profileId)
        .collectAsState(initial = emptySet())
    val activeProfileLikes by likesRepository
        .observeLikes(activeRecommendationProfile.profileId)
        .collectAsState(initial = emptyList())
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
    val cacheSnapshot by cacheRepository.observeSnapshot().collectAsState(
        initial = CacheSnapshot(thumbnailCount = 0, fullImageCount = 0),
    )
    val codices by codexRepository.observeCodices().collectAsState(initial = emptyList())
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

    var viewerSession by remember { mutableStateOf<ViewerSession?>(null) }
    var showSaveSheet by remember { mutableStateOf(false) }
    var pendingSavePost by remember { mutableStateOf<Post?>(null) }
    var homeTabRoute by rememberSaveable { mutableStateOf(TopLevelDestination.Search.route) }
    var pendingTopLevelRoute by remember { mutableStateOf<String?>(null) }
    var homeTabRestoreComplete by remember { mutableStateOf(false) }
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
    var pendingPostDeepLinkUri by remember { mutableStateOf<Uri?>(null) }
    var pendingCodexImportUri by remember { mutableStateOf<Uri?>(null) }
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
        settingsRepository,
        activeRecommendationProfile.profileId,
        appContext,
    ) {
        { source, tag ->
            scope.launch {
                val added = settingsRepository.addFavoriteTag(
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
        settingsRepository,
        activeRecommendationProfile.profileId,
    ) {
        { source, tag ->
            scope.launch {
                settingsRepository.removeFavoriteTag(
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

        val rule34XxxCredentials = credentialsStore.getRule34XxxCredentials()
        rule34XxxConfigured = rule34XxxCredentials != null
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
                val postToDownload = when {
                    !requiresLazyMediaResolution(post) -> post
                    else -> {
                        val adapter = realRegistry.adapterFor(post.id.source)
                        runCatching { adapter?.resolvePost(post.id) }.getOrNull()?.also { resolved ->
                            searchCoordinator.rememberResolvedPost(resolved)
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
        creatorProfileCoordinator.open(creator)
        if (navController.currentBackStackEntry?.destination?.route == AppRoute.Viewer) {
            viewerSession = null
            searchCoordinator.setViewerLaunchContext(null)
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
            val adapter = realRegistry.adapterFor(post.id.source)
            val resolved = runCatching { adapter?.resolvePost(post.id) }.getOrNull()
            if (resolved != null) {
                searchCoordinator.rememberResolvedPost(resolved)
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
        viewerSession = viewerSession?.let { session ->
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
                val response = runCatching {
                    sourceHttpClient.get(
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
        val codexName = if (profile.name.equals(DEFAULT_PROFILE_NAME, ignoreCase = true)) {
            LIKES_CODEX_NAME
        } else {
            "$LIKES_CODEX_NAME (${profile.name})"
        }
        return codexRepository.ensureCodex(
            codexId = likesCodexIdForProfile(profile.profileId),
            name = codexName,
        ).codexId
    }

    suspend fun toggleLikeAndSyncCodex(post: Post) {
        val nowLiked = likesRepository.toggleLike(
            profileId = activeRecommendationProfile.profileId,
            postId = post.id,
            tags = trainingTagsFor(post),
        )
        val likesCodexId = ensureLikesCodexId(activeRecommendationProfile)
        if (nowLiked) {
            codexRepository.addItem(likesCodexId, post)
            return
        }

        codexRepository.removeItem(
            codexId = likesCodexId,
            sourceKey = post.id.source,
            sourcePostId = post.id.sourcePostId,
        )
    }

    suspend fun clearProfileLikesAndSyncCodex(profileId: String) {
        val likesToClear = likesRepository.observeLikes(profileId).first()
        likesRepository.clearLikes(profileId)
        if (likesToClear.isEmpty()) return

        val likesCodexId = likesCodexIdForProfile(profileId)
        likesToClear.forEach { liked ->
            codexRepository.removeItem(
                codexId = likesCodexId,
                sourceKey = liked.postId.source,
                sourcePostId = liked.postId.sourcePostId,
            )
        }
    }

    suspend fun removeProfileLikesCodex(profileId: String) {
        val codexId = likesCodexIdForProfile(profileId)
        codexRepository.observeCodex(codexId).first()?.let {
            codexRepository.deleteCodex(codexId)
        }
    }

    suspend fun removeProfileScopedCodices(profileId: String) {
        val prefix = "${PROFILE_CODEX_ID_PREFIX}_${profileId}_"
        codices
            .filter { codex -> codex.codexId.startsWith(prefix) }
            .forEach { codex ->
                codexRepository.deleteCodex(codex.codexId)
            }
    }

    suspend fun searchFromCodex(source: SourceKey, includeTags: List<String>) {
        if (source !in realRegistry.availableSources()) {
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

        if (
            !searchCoordinator.prepareTagSearch(
                includeTags = normalizedIncludeTags,
                mode = QueryMode.Source(source),
            )
        ) {
            Toast.makeText(appContext, "${source.displayName()} source is unavailable", Toast.LENGTH_SHORT).show()
            return
        }

        searchCoordinator.applyDraft()
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
        val posts = codexRepository
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
        val codex = codexRepository.observeCodex(codexId).first()
        if (codex == null) {
            Toast.makeText(appContext, "Codex not found", Toast.LENGTH_SHORT).show()
            return
        }
        val posts = codexRepository.observeCodexPosts(codexId, CodexSortMode.NEWEST_SAVED).first()
        val export = buildCodexShareFile(title = codex.name, posts = posts)

        val exportsDirectory = storageDirectory.resolve("exports").apply { mkdirs() }
        val fileName = "${sanitizeCodexExportName(codex.name)}.json"
        val exportFile = exportsDirectory.resolve(fileName)
        runCatching {
            exportFile.writeText(Gson().toJson(export))
            val contentUri = FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                exportFile,
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                putExtra(Intent.EXTRA_SUBJECT, codex.name)
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

        val parsed = parseCodexShareFile(raw)
        val title = parsed?.title?.trim().orEmpty()
        if (title.isBlank()) {
            Toast.makeText(appContext, "Invalid codex file", Toast.LENGTH_SHORT).show()
            return
        }

        val profileId = activeRecommendationProfile.profileId
        val codex = codexRepository.ensureCodex(
            codexId = profileScopedCodexId(profileId),
            name = title,
        )

        val entries = selectCodexShareEntries(parsed?.posts.orEmpty())

        if (entries.isEmpty()) {
            Toast.makeText(appContext, "Imported codex with no posts", Toast.LENGTH_SHORT).show()
            return
        }

        refreshSourceAccountState()

        var imported = 0
        entries.forEach { (entry, postId) ->
            val resolvedFromSource = realRegistry.adapterFor(postId.source)?.let { adapter ->
                runCatching { adapter.resolvePost(postId) }.getOrNull()
            }
            val resolved = resolveCodexShareImportPost(
                entry = entry,
                resolvedFromSource = resolvedFromSource,
                storedPost = { codexRepository.getPost(postId) },
            )
                ?: return@forEach

            codexRepository.addItem(codex.codexId, resolved)
            cacheRepository.cacheThumbnail(resolved)
            imported += 1
        }
        val skipped = entries.size - imported
        Toast.makeText(
            appContext,
            "Imported $imported posts${if (skipped > 0) " ($skipped skipped)" else ""}",
            Toast.LENGTH_SHORT,
        ).show()
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
            codexRepository.reorderCodex(codex.codexId, index)
        }
    }

    suspend fun completeAppStartup() {
        if (navReady) return
        searchCoordinator.initialize()
        forYouCoordinator.initialize()
        ensureLikesCodexId(activeRecommendationProfile)
        refreshSourceAccountState()
        homeTabRoute = uiRestoreRepository.getLastTab()
            ?.takeIf { route -> TopLevelDestination.entries.any { destination -> destination.route == route } }
            ?: settingsRepository.observeSettings().first().lastSelectedTabRoute
                .takeIf { route -> TopLevelDestination.entries.any { destination -> destination.route == route } }
            ?: TopLevelDestination.Search.route
        navReady = true
        scope.launch {
            searchCoordinator.restoreLastAppliedSearchIfNeeded()
        }
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

    fun requestViewerPostResolution(post: Post) {
        val streamSource = viewerSession?.context?.streamSource ?: ViewerStreamSource.SEARCH
        if (!requiresViewerPostResolution(post, streamSource)) return
        scope.launch {
            val adapter = realRegistry.adapterFor(post.id.source) ?: return@launch
            val resolved = runCatching { adapter.resolvePost(post.id) }.getOrNull() ?: return@launch
            when (viewerSession?.context?.streamSource) {
                ViewerStreamSource.SEARCH -> searchCoordinator.rememberResolvedPost(resolved)
                ViewerStreamSource.FOR_YOU -> forYouCoordinator.rememberResolvedPost(resolved)
                ViewerStreamSource.CREATOR_PROFILE -> creatorProfileCoordinator.rememberResolvedPost(resolved)
                ViewerStreamSource.CODEX, ViewerStreamSource.RECENTS, null -> codexRepository.updatePost(resolved)
            }
            viewerSession = viewerSession?.let { session ->
                val index = session.posts.indexOfFirst { current -> current.id == post.id }
                if (index < 0) return@let session
                session.copy(
                    posts = session.posts.toMutableList().apply {
                        this[index] = resolved
                    },
                )
            }
        }
    }

    fun requestViewerMediaRecovery(post: Post, failedMedia: ImageRef) {
        scope.launch {
            val recovered = try {
                recoverRemoteMedia(realRegistry, post, failedMedia)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            } ?: return@launch
            val activeSession = viewerSession ?: return@launch
            if (activeSession.posts.none { current -> current.id == post.id }) return@launch
            when (activeSession.context.streamSource) {
                ViewerStreamSource.SEARCH -> searchCoordinator.rememberResolvedPost(recovered)
                ViewerStreamSource.FOR_YOU -> forYouCoordinator.rememberResolvedPost(recovered)
                ViewerStreamSource.CREATOR_PROFILE -> creatorProfileCoordinator.rememberResolvedPost(recovered)
                ViewerStreamSource.CODEX, ViewerStreamSource.RECENTS -> codexRepository.updatePost(recovered)
            }
            viewerSession = viewerSession?.let { session ->
                val index = session.posts.indexOfFirst { current -> current.id == post.id }
                if (index < 0) return@let session
                session.copy(
                    posts = session.posts.toMutableList().apply {
                        this[index] = recovered
                    },
                )
            }
        }
    }

    suspend fun prepareViewerPostsForLaunch(
        posts: List<Post>,
        context: ViewerLaunchContext,
    ): List<Post> {
        if (posts.isEmpty()) return posts
        val startIndex = context.startIndex.coerceIn(0, posts.lastIndex)
        val selectedPost = posts[startIndex]
        if (!requiresPrelaunchViewerPostResolution(selectedPost, context.streamSource)) return posts
        val adapter = realRegistry.adapterFor(selectedPost.id.source) ?: return posts
        val resolved = runCatching { adapter.resolvePost(selectedPost.id) }.getOrNull() ?: return posts
        when (context.streamSource) {
            ViewerStreamSource.SEARCH -> searchCoordinator.rememberResolvedPost(resolved)
            ViewerStreamSource.FOR_YOU -> forYouCoordinator.rememberResolvedPost(resolved)
            ViewerStreamSource.CREATOR_PROFILE -> creatorProfileCoordinator.rememberResolvedPost(resolved)
            ViewerStreamSource.CODEX, ViewerStreamSource.RECENTS -> codexRepository.updatePost(resolved)
        }
        return posts.toMutableList().apply {
            this[startIndex] = resolved
        }
    }

    suspend fun recordVisibleViewerPost(post: Post) {
        val context = viewerSession?.context
        recentsRepository.recordWatchedPost(
            post = post,
            origin = context?.streamSource ?: ViewerStreamSource.SEARCH,
            originQueryHash = context?.queryHash,
        )
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

    LaunchedEffect(searchCoordinator, forYouCoordinator, navReady) {
        if (!navReady) return@LaunchedEffect
        searchCoordinator.initialize()
        forYouCoordinator.initialize()
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
        } else if (isCodexImportUri(appContext, uri)) {
            pendingCodexImportUri = uri
        } else {
            pendingPostDeepLinkUri = uri
        }
        onIncomingUriConsumed()
    }

    LaunchedEffect(navReady, pendingCodexImportUri) {
        if (!navReady) return@LaunchedEffect
        val uri = pendingCodexImportUri ?: return@LaunchedEffect
        fun consumePendingImportUriIfCurrent() {
            if (pendingCodexImportUri == uri) {
                pendingCodexImportUri = null
            }
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

    LaunchedEffect(navReady, pendingPostDeepLinkUri) {
        if (!navReady) return@LaunchedEffect
        val uri = pendingPostDeepLinkUri ?: return@LaunchedEffect
        fun consumePendingUriIfCurrent() {
            if (pendingPostDeepLinkUri == uri) {
                pendingPostDeepLinkUri = null
            }
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
        val adapter = realRegistry.adapterFor(requiredDeepLink.source)
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
        val shouldRefreshSearch = searchCoordinator.onSettingsChanged(settings)
        if (shouldRefreshSearch) {
            searchCoordinator.retry()
        }

        val shouldRefreshForYou = forYouCoordinator.onSettingsChanged(settings)
        if (shouldRefreshForYou) {
            if (activeProfileLikes.isEmpty()) {
                forYouCoordinator.clear()
            } else {
                forYouCoordinator.refresh(shuffle = false)
            }
        }
    }

    LaunchedEffect(codices.map { it.codexId }, availableRealSources) {
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

        coroutineScope {
            codices.forEach { codex ->
                launch {
                    codexRepository.observeCodexItems(codex.codexId).collect { items ->
                        codexItemCounts[codex.codexId] = items.size
                        savedPostIdsByCodex[codex.codexId] = items
                            .asSequence()
                            .map { item -> item.postId }
                            .toSet()
                    }
                }
                launch {
                    codexRepository.observeCodexPosts(codex.codexId, CodexSortMode.NEWEST_SAVED).collect { posts ->
                        val coverModel = posts.firstOrNull()?.let { post ->
                            resolveCodexCoverModel(
                                storageDirectory = storageDirectory,
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
    val lifecycleOwner = LocalLifecycleOwner.current
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
            settingsRepository.setLastTab(route)
            uiRestoreRepository.setLastTab(route)
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
        searchCoordinator.displayResultsVersion,
        forYouCoordinator.results,
        creatorProfileCoordinator.results,
        creatorProfileCoordinator.activeQueryHash,
        searchCoordinator.appliedQueryHash,
        viewerSession?.context?.streamSource,
        viewerSession?.liveSearchBinding,
        viewerSession?.searchVisibilityFilters,
        likedPostIds,
        savedPostIds,
        unknownAnimatedDurationPolicy,
    ) {
        val session = viewerSession ?: return@LaunchedEffect
        if (!session.liveSearchBinding) return@LaunchedEffect
        val incomingForViewer = when (session.context.streamSource) {
            ViewerStreamSource.SEARCH -> {
                if (session.context.queryHash != searchCoordinator.appliedQueryHash) return@LaunchedEffect
                filterSearchResults(
                    results = searchCoordinator.displayResults(),
                    filters = session.searchVisibilityFilters,
                    likedPostIds = likedPostIds,
                    savedPostIds = savedPostIds,
                    unknownAnimatedDurationPolicy = unknownAnimatedDurationPolicy,
                )
            }

            ViewerStreamSource.FOR_YOU -> {
                if (!session.context.queryHash.startsWith("for_you:")) return@LaunchedEffect
                filterSearchResults(
                    results = forYouCoordinator.results,
                    filters = session.searchVisibilityFilters,
                    likedPostIds = emptySet(),
                    savedPostIds = emptySet(),
                    unknownAnimatedDurationPolicy = unknownAnimatedDurationPolicy,
                )
            }

            ViewerStreamSource.CREATOR_PROFILE -> {
                if (session.context.queryHash != creatorProfileCoordinator.activeQueryHash) return@LaunchedEffect
                filterSearchResults(
                    results = creatorProfileCoordinator.results,
                    filters = session.searchVisibilityFilters,
                    likedPostIds = likedPostIds,
                    savedPostIds = savedPostIds,
                    unknownAnimatedDurationPolicy = unknownAnimatedDurationPolicy,
                )
            }

            ViewerStreamSource.CODEX, ViewerStreamSource.RECENTS -> return@LaunchedEffect
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
                                    SearchScreen(
                                        coordinator = searchCoordinator,
                                        pixivUgoiraClient = pixivUgoiraClient,
                                        likedPostIds = likedPostIds,
                                        savedPostIds = savedPostIds,
                                        favoriteTags = activeProfileFavoriteTags,
                                        resolveUnknownAnimatedDurations = settings.contentFilters.resolveUnknownAnimatedDurations,
                                        onToggleLike = { post ->
                                            scope.launch {
                                                toggleLikeAndSyncCodex(post)
                                            }
                                        },
                                        onOpenViewer = { posts, context, visibilityFilters ->
                                            scope.launch {
                                                val preparedPosts = prepareViewerPostsForLaunch(posts, context)
                                                viewerSession = ViewerSession(
                                                    posts = preparedPosts,
                                                    context = context,
                                                    liveSearchBinding = true,
                                                    searchVisibilityFilters = visibilityFilters,
                                                )
                                                searchCoordinator.setViewerLaunchContext(context)
                                                navController.navigate(AppRoute.Viewer)
                                            }
                                        },
                                        onRequestSaveToCodex = { post ->
                                            pendingSavePost = post
                                            showSaveSheet = true
                                        },
                                        onSaveToDevice = { post ->
                                            requestSaveToDevice(post)
                                        },
                                        onAddFavoriteTag = addFavoriteTag,
                                        onRemoveFavoriteTag = removeFavoriteTag,
                                        onOpenCreatorProfile = { creator ->
                                            scope.launch { openCreatorProfile(creator) }
                                        },
                                        onOpenLegacyCreatorProfile = { post ->
                                            scope.launch { openCreatorProfile(post) }
                                        },
                                        onApplySearch = {
                                            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                                val directNhentaiId = searchCoordinator.directNhentaiGalleryIdCandidate()
                                                searchCoordinator.applyDraft()
                                                if (directNhentaiId != null) {
                                                    val resolved = searchCoordinator.results.firstOrNull { post ->
                                                        post.id.source == SourceKey.NHENTAI &&
                                                            post.id.sourcePostId == directNhentaiId
                                                    } ?: runCatching {
                                                        searchCoordinator.resolveNhentaiGalleryById(directNhentaiId)
                                                    }.getOrNull()

                                                    if (resolved != null) {
                                                        val context = ViewerLaunchContext(
                                                            queryHash = "nhentai-search-id:$directNhentaiId",
                                                            startIndex = 0,
                                                            streamSource = ViewerStreamSource.SEARCH,
                                                            scrollOffsetHint = 0,
                                                        )
                                                        viewerSession = ViewerSession(
                                                            posts = listOf(resolved),
                                                            context = context,
                                                            liveSearchBinding = false,
                                                        )
                                                        searchCoordinator.setViewerLaunchContext(context)
                                                        navController.navigate(AppRoute.Viewer) {
                                                            launchSingleTop = true
                                                        }
                                                    }
                                                }
                                            }
                                        },
                                        onRetrySearch = {
                                            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                                searchCoordinator.retry()
                                            }
                                        },
                                    )
                                }

                                TopLevelDestination.ForYou -> {
                                    ForYouScreen(
                                        coordinator = forYouCoordinator,
                                        activeProfileId = activeRecommendationProfile.profileId,
                                        likesCount = activeProfileLikes.size,
                                        likedPostIds = likedPostIds,
                                        pixivUgoiraClient = pixivUgoiraClient,
                                        resolveUnknownAnimatedDurations = settings.contentFilters.resolveUnknownAnimatedDurations,
                                        onToggleLike = { post ->
                                            scope.launch {
                                                toggleLikeAndSyncCodex(post)
                                            }
                                        },
                                        onBlacklistCurrentSeed = {
                                            scope.launch {
                                                if (forYouCoordinator.loading) return@launch
                                                val added = forYouCoordinator.blacklistCurrentSeedAndRefresh()
                                                if (added > 0) {
                                                    Toast.makeText(
                                                        appContext,
                                                        "Blacklisted $added recommendation tag set${if (added > 1) "s" else ""}",
                                                        Toast.LENGTH_SHORT,
                                                    ).show()
                                                } else {
                                                    Toast.makeText(
                                                        appContext,
                                                        "Current recommendation is already blacklisted",
                                                        Toast.LENGTH_SHORT,
                                                    ).show()
                                                }
                                            }
                                        },
                                        onOpenViewer = { posts, context, visibilityFilters ->
                                            scope.launch {
                                                val preparedPosts = prepareViewerPostsForLaunch(posts, context)
                                                viewerSession = ViewerSession(
                                                    posts = preparedPosts,
                                                    context = context,
                                                    liveSearchBinding = true,
                                                    searchVisibilityFilters = visibilityFilters,
                                                )
                                                searchCoordinator.setViewerLaunchContext(context)
                                                navController.navigate(AppRoute.Viewer)
                                            }
                                        },
                                        onGoToSearch = {
                                            val targetIndex = TopLevelDestination.entries.indexOf(TopLevelDestination.Search)
                                            homeTabRoute = TopLevelDestination.Search.route
                                            scope.launch {
                                                if (topLevelPagerState.currentPage != targetIndex) {
                                                    topLevelPagerState.animateScrollToPage(targetIndex)
                                                }
                                            }
                                        },
                                    )
                                }

                                TopLevelDestination.Recents -> {
                                    RecentsScreen(
                                        watchedPosts = recentWatchedPosts,
                                        searches = recentSearches,
                                        activity = recentActivity,
                                        pixivUgoiraClient = pixivUgoiraClient,
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
                                                    val preparedPosts = prepareViewerPostsForLaunch(posts, context)
                                                    viewerSession = ViewerSession(
                                                        posts = preparedPosts,
                                                        context = context,
                                                        liveSearchBinding = false,
                                                    )
                                                    uiRestoreRepository.setViewerLaunchContext(context)
                                                    navController.navigate(AppRoute.Viewer)
                                                }
                                            }
                                        },
                                        onOpenSearch = { entry ->
                                            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                                val applied = searchCoordinator.applyHistoricalQuery(entry.query)
                                                if (!applied) {
                                                    Toast.makeText(
                                                        appContext,
                                                        "Search source is unavailable",
                                                        Toast.LENGTH_SHORT,
                                                    ).show()
                                                    return@launch
                                                }
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
                                            scope.launch { recentsRepository.clearWatchedPosts() }
                                        },
                                        onClearSearches = {
                                            scope.launch { recentsRepository.clearSearches() }
                                        },
                                        onClearAll = {
                                            scope.launch { recentsRepository.clearAll() }
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
                                                codexRepository.ensureCodex(
                                                    codexId = profileScopedCodexId(activeRecommendationProfile.profileId),
                                                    name = name,
                                                )
                                            }
                                        },
                                        onRenameCodex = { codexId, name ->
                                            scope.launch { codexRepository.renameCodex(codexId, name) }
                                        },
                                        onDeleteCodex = { codexId ->
                                            scope.launch { codexRepository.deleteCodex(codexId) }
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
                                        availableSources = searchCoordinator.availableSources,
                                        providerHealth = settings.providerHealth,
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
                                            scope.launch {
                                                if (rule34XxxUserIdInput.isBlank() || rule34XxxApiKeyInput.isBlank()) {
                                                    rule34XxxStatusLabel = "Missing user ID or API key"
                                                } else {
                                                    credentialsStore.saveRule34XxxCredentials(
                                                        Rule34XxxCredentials(
                                                            userId = rule34XxxUserIdInput,
                                                            apiKey = rule34XxxApiKeyInput,
                                                        )
                                                    )
                                                    refreshSourceAccountState()
                                                    rule34XxxStatusLabel = "Configured"
                                                }
                                            }
                                        },
                                        onClearRule34XxxCredentials = {
                                            scope.launch {
                                                credentialsStore.clearRule34XxxCredentials()
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
                                        onSetActiveProfile = { profileId ->
                                            scope.launch { settingsRepository.setActiveProfile(profileId) }
                                        },
                                        onAddProfile = { name ->
                                            scope.launch { settingsRepository.addRecommendationProfile(name) }
                                        },
                                        onRemoveProfile = { profileId ->
                                            scope.launch {
                                                val canRemove = settings.recommendationProfiles.size > 1 &&
                                                    settings.recommendationProfiles.any { profile -> profile.profileId == profileId }
                                                if (!canRemove) return@launch
                                                clearProfileLikesAndSyncCodex(profileId)
                                                removeProfileLikesCodex(profileId)
                                                removeProfileScopedCodices(profileId)
                                                settingsRepository.removeRecommendationProfile(profileId)
                                            }
                                        },
                                        onClearLikesForActiveProfile = {
                                            scope.launch { clearProfileLikesAndSyncCodex(activeRecommendationProfile.profileId) }
                                        },
                                        onRemoveForYouBlacklistEntry = { source, tags ->
                                            scope.launch {
                                                settingsRepository.removeForYouBlacklistEntry(
                                                    profileId = activeRecommendationProfile.profileId,
                                                    source = source,
                                                    tags = tags,
                                                )
                                            }
                                        },
                                        onSetCacheFullImageOnSave = { enabled ->
                                            scope.launch { settingsRepository.setCacheFullImageOnSave(enabled) }
                                        },
                                        onSetResolveUnknownAnimatedDurations = { enabled ->
                                            scope.launch { settingsRepository.setResolveUnknownAnimatedDurations(enabled) }
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
                            }
                        }
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
                            tagVideoCountProvider = { source, tag ->
                                searchCoordinator.tagVideoCount(source, tag)
                            },
                            fetchTagVideoCounts = { source, tags ->
                                searchCoordinator.fetchTagVideoCounts(source, tags)
                            },
                            onSortChange = { sortMode = it },
                            resolvePostById = resolver@{ postId ->
                                val adapter = realRegistry.adapterFor(postId.source) ?: return@resolver null
                                val resolved = runCatching { adapter.resolvePost(postId) }.getOrNull() ?: return@resolver null
                                codexRepository.updatePost(resolved)
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
                                    val preparedPosts = prepareViewerPostsForLaunch(posts, context)
                                    viewerSession = ViewerSession(
                                        posts = preparedPosts,
                                        context = context,
                                        liveSearchBinding = false,
                                    )
                                    uiRestoreRepository.setViewerLaunchContext(context)
                                    navController.navigate(AppRoute.Viewer)
                                }
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
                            onOpenCreatorProfile = { creator ->
                                scope.launch { openCreatorProfile(creator) }
                            },
                            onOpenLegacyCreatorProfile = { post ->
                                scope.launch { openCreatorProfile(post) }
                            },
                            onAddIncludeTerm = { post, term ->
                                searchCoordinator.addPostIncludeTerm(post, term)
                            },
                            onAddExcludeTerm = { post, term ->
                                searchCoordinator.addPostExcludeTerm(post, term)
                            },
                            onRemoveIncludeTerm = { _, term ->
                                searchCoordinator.removeIncludeTerm(term)
                            },
                            onRemoveExcludeTerm = { _, term ->
                                searchCoordinator.removeExcludeTerm(term)
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
                                    codexRepository.deleteCodex(codexId)
                                    homeTabRoute = TopLevelDestination.Codex.route
                                    navController.popBackStack(AppRoute.Home, inclusive = false)
                                }
                            },
                        )
                    }
                    composable(AppRoute.CreatorProfile) {
                        CreatorProfileScreen(
                            coordinator = creatorProfileCoordinator,
                            likedPostIds = likedPostIds,
                            savedPostIds = savedPostIds,
                            pixivUgoiraClient = pixivUgoiraClient,
                            resolveUnknownAnimatedDurations = settings.contentFilters.resolveUnknownAnimatedDurations,
                            onToggleLike = { post ->
                                scope.launch { toggleLikeAndSyncCodex(post) }
                            },
                            onOpenViewer = { posts, context, visibilityFilters ->
                                scope.launch {
                                    val preparedPosts = prepareViewerPostsForLaunch(posts, context)
                                    viewerSession = ViewerSession(
                                        posts = preparedPosts,
                                        context = context,
                                        liveSearchBinding = true,
                                        searchVisibilityFilters = visibilityFilters,
                                    )
                                    navController.navigate(AppRoute.Viewer)
                                }
                            },
                            onRequestSaveToCodex = { post ->
                                pendingSavePost = post
                                showSaveSheet = true
                            },
                            onSaveToDevice = { post ->
                                requestSaveToDevice(post)
                            },
                            onOpenUrl = { url ->
                                openInBrowser(appContext, url)
                            },
                            onAddIncludeTerm = { post, term ->
                                searchCoordinator.addPostIncludeTerm(post, term)
                            },
                            onAddExcludeTerm = { post, term ->
                                searchCoordinator.addPostExcludeTerm(post, term)
                            },
                            onRemoveIncludeTerm = { _, term ->
                                searchCoordinator.removeIncludeTerm(term)
                            },
                            onRemoveExcludeTerm = { _, term ->
                                searchCoordinator.removeExcludeTerm(term)
                            },
                            onBack = {
                                navController.popBackStack()
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
                            val canLoadMoreFromSource = when (session.context.streamSource) {
                                ViewerStreamSource.SEARCH -> session.liveSearchBinding && searchCoordinator.canLoadMore
                                ViewerStreamSource.FOR_YOU -> session.liveSearchBinding && forYouCoordinator.canLoadMore
                                ViewerStreamSource.CREATOR_PROFILE -> session.liveSearchBinding && creatorProfileCoordinator.canLoadMore
                                ViewerStreamSource.CODEX, ViewerStreamSource.RECENTS -> false
                            }
                            val loadingMoreFromSource = when (session.context.streamSource) {
                                ViewerStreamSource.SEARCH -> searchCoordinator.loadingMore
                                ViewerStreamSource.FOR_YOU -> forYouCoordinator.loadingMore
                                ViewerStreamSource.CREATOR_PROFILE -> creatorProfileCoordinator.loadingMore
                                ViewerStreamSource.CODEX, ViewerStreamSource.RECENTS -> false
                            }
                            val onLoadMoreFromSource = when (session.context.streamSource) {
                                ViewerStreamSource.SEARCH -> {
                                    if (session.liveSearchBinding) {
                                        {
                                            scope.launch { searchCoordinator.loadNextPage() }
                                            Unit
                                        }
                                    } else {
                                        null
                                    }
                                }

                                ViewerStreamSource.FOR_YOU -> {
                                    if (session.liveSearchBinding) {
                                        {
                                            scope.launch { forYouCoordinator.loadNextPage() }
                                            Unit
                                        }
                                    } else {
                                        null
                                    }
                                }

                                ViewerStreamSource.CREATOR_PROFILE -> {
                                    if (session.liveSearchBinding) {
                                        {
                                            scope.launch { creatorProfileCoordinator.loadNextPage() }
                                            Unit
                                        }
                                    } else {
                                        null
                                    }
                                }

                                ViewerStreamSource.CODEX, ViewerStreamSource.RECENTS -> null
                            }

                            ViewerScreen(
                                posts = session.posts,
                                launchContext = session.context,
                                pixivUgoiraClient = pixivUgoiraClient,
                                tagVideoCountProvider = { source, tag ->
                                    searchCoordinator.tagVideoCount(source, tag)
                                },
                                fetchTagVideoCounts = { source, tags ->
                                    searchCoordinator.fetchTagVideoCounts(source, tags)
                                },
                                canLoadMoreFromSource = canLoadMoreFromSource,
                                loadingMoreFromSource = loadingMoreFromSource,
                                onLoadMoreFromSource = onLoadMoreFromSource,
                                invertMultiImageScrollDirection = settings.viewer.invertMultiImageScrollDirection,
                                onInvertMultiImageScrollDirectionChange = { enabled ->
                                    scope.launch {
                                        settingsRepository.setInvertMultiImageScrollDirection(enabled)
                                    }
                                },
                                likedPostIds = likedPostIds,
                                onToggleLike = { post ->
                                    scope.launch {
                                        toggleLikeAndSyncCodex(post)
                                    }
                                },
                                onRequestPostResolution = ::requestViewerPostResolution,
                                onRequestMediaRecovery = ::requestViewerMediaRecovery,
                                onVisiblePostChanged = { post ->
                                    scope.launch { recordVisibleViewerPost(post) }
                                },
                                onDismiss = {
                                    viewerSession = null
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
                                onAddIncludeTerm = { post, term ->
                                    searchCoordinator.addPostIncludeTerm(post, term)
                                },
                                onAddExcludeTerm = { post, term ->
                                    searchCoordinator.addPostExcludeTerm(post, term)
                                },
                                onRemoveIncludeTerm = { _, term ->
                                    searchCoordinator.removeIncludeTerm(term)
                                },
                                onRemoveExcludeTerm = { _, term ->
                                    searchCoordinator.removeExcludeTerm(term)
                                },
                                onFavoriteTagLongPress = addFavoriteTag,
                                onGoToSearch = {
                                    viewerSession = null
                                    homeTabRoute = TopLevelDestination.Search.route
                                    scope.launch { searchCoordinator.setViewerLaunchContext(null) }
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
                                onOpenCreatorProfile = { creator ->
                                    scope.launch { openCreatorProfile(creator) }
                                },
                                onOpenLegacyCreatorProfile = { post ->
                                    scope.launch { openCreatorProfile(post) }
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
                profiles = settings.recommendationProfiles,
                initialProfileId = activeRecommendationProfile.profileId,
                codicesByProfile = saveSheetCodicesByProfile,
                codexItemCounts = codexItemCounts,
                codexCoverModels = codexCoverModels,
                onCreateCodex = { profileId, name ->
                    scope.launch {
                        val codex = codexRepository.ensureCodex(
                            codexId = profileScopedCodexId(profileId),
                            name = name,
                        )
                        codexRepository.addItem(codex.codexId, post)
                        cacheRepository.cacheThumbnail(post)
                        if (settings.cache.cacheFullImageOnSave) {
                            cacheRepository.cacheFull(post)
                        }
                    }
                    showSaveSheet = false
                    pendingSavePost = null
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

private fun likesCodexIdForProfile(profileId: String): String {
    return if (profileId == DEFAULT_MAIN_PROFILE_ID) {
        LIKES_CODEX_ID_PREFIX
    } else {
        "${LIKES_CODEX_ID_PREFIX}_$profileId"
    }
}

private fun profileScopedCodexId(profileId: String): String {
    return "${PROFILE_CODEX_ID_PREFIX}_${profileId}_${UUID.randomUUID()}"
}

private fun codexBelongsToProfile(codexId: String, profileId: String): Boolean {
    if (codexId.startsWith(LIKES_CODEX_ID_PREFIX)) {
        return codexId == likesCodexIdForProfile(profileId)
    }
    if (codexId.startsWith("${PROFILE_CODEX_ID_PREFIX}_")) {
        return codexId.startsWith("${PROFILE_CODEX_ID_PREFIX}_${profileId}_")
    }
    return profileId == DEFAULT_MAIN_PROFILE_ID
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
private const val BOTTOM_BAR_HEIGHT_RATIO = 0.085f
private const val BOTTOM_BAR_ICON_RATIO = 0.38f
private const val MIN_BOTTOM_BAR_HEIGHT_DP = 68
private const val MAX_BOTTOM_BAR_HEIGHT_DP = 88
private const val MIN_BOTTOM_BAR_ICON_DP = 24
private const val MAX_BOTTOM_BAR_ICON_DP = 30
private const val DEFAULT_MAIN_PROFILE_ID = "profile-main"
private const val DEFAULT_PROFILE_NAME = "Main"
private const val LIKES_CODEX_ID_PREFIX = "system_likes_codex"
private const val PROFILE_CODEX_ID_PREFIX = "profile_codex"
private const val LIKES_CODEX_NAME = "Likes"
private val CODEX_IMPORT_MIME_TYPES = setOf("application/json", "text/json")
private val GELBOORU_PROFILE_OWNER_REGEX = Regex("""user:([A-Za-z0-9_:-]+)""", RegexOption.IGNORE_CASE)
