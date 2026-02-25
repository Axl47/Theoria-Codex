package com.theoriacodex.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.JsonObject
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.theoriacodex.app.codex.CodexDetailScreen
import com.theoriacodex.app.codex.CodexListScreen
import com.theoriacodex.app.codex.SaveToCodexSheet
import com.theoriacodex.app.explore.ExploreScreen
import com.theoriacodex.app.search.SearchCoordinator
import com.theoriacodex.app.search.SearchScreen
import com.theoriacodex.app.search.FileBackedTagSuggestionStore
import com.theoriacodex.app.settings.SettingsScreen
import com.theoriacodex.app.sourceauth.AndroidSecureSourceCredentialsStore
import com.theoriacodex.app.sourceauth.PixivPkceController
import com.theoriacodex.app.ui.theme.TheoriaNightTheme
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
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.RealAdapterRegistry
import com.theoriacodex.sources.credentials.GelbooruCredentials
import com.theoriacodex.sources.http.DefaultSourceHttpClient
import com.theoriacodex.sources.pixiv.PixivAuthApi
import java.io.File
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
)

@Composable
fun TheoriaApp(
    authCallbackUri: Uri? = null,
    onAuthCallbackConsumed: () -> Unit = {},
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
    val credentialsStore = remember(appContext) { AndroidSecureSourceCredentialsStore(appContext) }
    val pixivAuthController = remember(credentialsStore, sourceHttpClient) {
        PixivPkceController(
            authApi = PixivAuthApi(sourceHttpClient),
            credentialsProvider = credentialsStore,
        )
    }
    val realRegistry = remember(credentialsStore, sourceHttpClient) {
        RealAdapterRegistry(
            credentialsProvider = credentialsStore,
            httpClient = sourceHttpClient,
            exposedSources = setOf(SourceKey.PIXIV),
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
    val codexItemCounts = remember { mutableStateMapOf<String, Int>() }

    var pixivStatusLabel by remember { mutableStateOf("Not connected") }
    var gelbooruStatusLabel by remember { mutableStateOf("Not configured") }
    var gelbooruUserIdInput by rememberSaveable { mutableStateOf("") }
    var gelbooruApiKeyInput by rememberSaveable { mutableStateOf("") }

    suspend fun refreshSourceAccountState() {
        val pixivTokens = credentialsStore.getPixivTokens()
        pixivStatusLabel = when {
            pixivTokens == null -> "Not connected"
            pixivTokens.expiresAtEpochMs <= System.currentTimeMillis() -> "Connected (token expired, refresh on demand)"
            else -> "Connected"
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

    LaunchedEffect(Unit) {
        searchCoordinator.initialize()
        refreshSourceAccountState()
        startDestination = uiRestoreRepository.getLastTab()
            ?: settingsRepository.observeSettings().first().lastSelectedTabRoute
        navReady = true
    }

    LaunchedEffect(authCallbackUri) {
        val callback = authCallbackUri ?: return@LaunchedEffect
        if (pixivAuthController.isAuthorizationCallback(callback)) {
            val result = pixivAuthController.handleAuthorizationCallback(callback)
            if (result.isSuccess) {
                pixivStatusLabel = "Connected"
                refreshSourceAccountState()
            } else {
                pixivStatusLabel = "Connection failed: ${result.exceptionOrNull()?.message ?: "Unknown error"}"
            }
        }
        onAuthCallbackConsumed()
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
    val showBottomBar = currentRoute in TopLevelDestination.entries.map { it.route }.toSet()

    LaunchedEffect(currentRoute) {
        if (currentRoute in TopLevelDestination.entries.map { it.route }) {
            val route = requireNotNull(currentRoute)
            settingsRepository.setLastTab(route)
            uiRestoreRepository.setLastTab(route)
        }
    }

    TheoriaNightTheme {
        if (!navReady) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            Scaffold(
                bottomBar = {
                    if (showBottomBar) {
                        NavigationBar(
                            modifier = Modifier.height(58.dp),
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
                                            modifier = Modifier.size(20.dp),
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
                    modifier = Modifier.padding(innerPadding),
                ) {
                    composable(TopLevelDestination.Search.route) {
                        SearchScreen(
                            coordinator = searchCoordinator,
                            onOpenViewer = { posts, context ->
                                viewerSession = ViewerSession(posts = posts, context = context)
                                scope.launch { searchCoordinator.setViewerLaunchContext(context) }
                                navController.navigate(AppRoute.Viewer)
                            },
                        )
                    }
                    composable(TopLevelDestination.Explore.route) {
                        ExploreScreen(
                            coordinator = searchCoordinator,
                            onNavigateToSearch = {
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
                            onSortChange = { sortMode = it },
                            onOpenViewer = { index ->
                                val context = ViewerLaunchContext(
                                    queryHash = "codex:$codexId",
                                    startIndex = index,
                                    streamSource = ViewerStreamSource.CODEX,
                                    scrollOffsetHint = 0,
                                )
                                viewerSession = ViewerSession(posts = posts, context = context)
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
                            onPixivConnect = {
                                val authUrl = pixivAuthController.startAuthorizationUri().toString()
                                pixivStatusLabel = "Awaiting authorization callback..."
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
                            onGelbooruApiKeyChange = { gelbooruApiKeyInput = it.trim() },
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
                                onGoToSearch = {
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
                val text = obj.get("text")?.asString?.trim().orEmpty()
                if (text.isBlank()) return@inner null
                TagSuggestion(
                    text = text,
                    type = obj.get("type")?.asString,
                    count = obj.get("count")?.asInt,
                )
            }
            .orEmpty()
        source to tags
    }.toMap()
}
